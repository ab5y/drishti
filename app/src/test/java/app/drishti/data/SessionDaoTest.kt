package app.drishti.data

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.drishti.core.AnswerSource
import app.drishti.core.Dir
import app.drishti.core.Eye
import app.drishti.core.Orientation
import app.drishti.core.Phase
import app.drishti.core.Referral
import app.drishti.core.SavedSession
import app.drishti.core.SessionRecord
import app.drishti.core.TestKind
import app.drishti.core.TrialRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory Room on the JVM through the bundled SQLite driver (D63). The
 * Context is an empty stub: an in-memory database never touches it.
 */
class SessionDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ContextWrapper(null), AppDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(AppDatabase.SecureDelete)
        .build()
    private val dao = db.sessions()

    @After
    fun close() = db.close()

    private fun record(id: String, createdAtMs: Long, schoolId: String? = "SCH-1") = SessionRecord(
        sessionId = id, createdAtMs = createdAtMs, timestamp = "2026-10-20T10:00:00+05:30", token = "ABC-234",
        schoolId = schoolId, age = null, consent = true, overall = Referral.REFER, overallText = "REFER (right eye).",
        rightReferral = Referral.REFER, rightLogMAR = null, rightAcuity = "no line passed", rightReason = "r",
        rightResponsesOk = true, rightScored = 5, rightUnsure = 0, rightQuality = "q1 | q2", rightRemoteDrops = 1,
        rightPinholeRun = true, rightPinholeReferral = Referral.PASS, rightPinholeLogMAR = 0.3, rightPinholeImproved = true,
        rightPinholeText = "Pinhole: improved", rightPinholeRemoteDrops = 0,
        leftReferral = Referral.PASS, leftLogMAR = 0.3, leftAcuity = "at least 6/12", leftReason = "l",
        leftResponsesOk = true, leftScored = 40, leftUnsure = 1, leftQuality = "q", leftRemoteDrops = 0,
        leftPinholeRun = false, leftPinholeReferral = null, leftPinholeLogMAR = null, leftPinholeImproved = null,
        leftPinholeText = null, leftPinholeRemoteDrops = 0,
        nearReferral = Referral.INCONCLUSIVE, nearLogMAR = null, nearAcuity = "not measured", nearReason = "n",
        nearResponsesOk = false, nearScored = 0, nearUnsure = 0, nearQuality = "q", nearRemoteDrops = 0,
        distanceOk = true, lightOk = null, responsesOk = false, luxAtRegister = 120.0, luxAtTestStart = null,
        remoteAnswers = true, appVersion = "0.1.0 (1)", configSchemaVersion = 1, configSha256 = "00", pxPerMm = 15.75,
        deviceId = "dev", remoteName = "Clicker", remoteMapping = "built-in defaults", decisionsRev = "D63",
    )

    private fun trial(id: String, seq: Int) = TrialRow(
        sessionId = id, seq = seq, test = TestKind.FAR, eye = Eye.RIGHT, phase = Phase.DESCENDING_LINES, logMAR = -0.1,
        orientation = Orientation.LEFT, response = Dir.UNSURE, correct = false, unsure = true, counted = true,
        distanceMm = 3000.0, intendedMm = 3.5, drawnMm = 3.49, strokePx = 11, sizeError = -0.003, crowded = true,
        source = AnswerSource.PAD,
    )

    private fun saved(id: String, at: Long, trials: Int = 3) = SavedSession(record(id, at), List(trials) { trial(id, it) })

    @Test
    fun `a session and its trials read back exactly`() = runBlocking {
        val s = saved("a", 1000)
        dao.insert(s)
        assertEquals(s.record, dao.session("a")?.record)
        assertEquals(s.trials, dao.trials(listOf("a")).map { it.row })
        assertEquals(3, dao.trialCount("a"))
    }

    @Test
    fun `sessions list newest first`() = runBlocking {
        dao.insert(saved("old", 1000))
        dao.insert(saved("new", 3000))
        dao.insert(saved("mid", 2000))
        assertEquals(listOf("new", "mid", "old"), dao.observeAll().first().map { it.record.sessionId })
        assertEquals(listOf("new", "old"), dao.sessions(listOf("old", "new")).map { it.record.sessionId })
    }

    @Test
    fun `deleting a session deletes its trials only`() = runBlocking {
        dao.insert(saved("a", 1000))
        dao.insert(saved("b", 2000))
        assertEquals(1, dao.delete("a"))
        assertNull(dao.session("a"))
        assertEquals(0, dao.trialCount("a"))
        assertEquals(3, dao.trialCount("b"))
        assertEquals(0, dao.delete("a"))
    }

    @Test
    fun `the foreign key cascades on its own`() = runBlocking {
        dao.insert(saved("a", 1000))
        dao.deleteSession("a")
        assertEquals(0, dao.trialCount("a"))
    }

    @Test
    fun `delete all empties both tables`() = runBlocking {
        dao.insert(saved("a", 1000))
        dao.insert(saved("b", 2000))
        dao.deleteAll()
        assertTrue(dao.allSessions().isEmpty())
        assertTrue(dao.allTrials().isEmpty())
    }

    @Test
    fun `a failed trial insert leaves no half session`() = runBlocking {
        val bad = SavedSession(record("a", 1000), listOf(trial("a", 0), trial("a", 0))) // duplicate primary key
        val failed = runCatching { dao.insert(bad) }.isFailure
        assertTrue(failed)
        assertNull(dao.session("a"))
        assertEquals(0, dao.trialCount("a"))
    }

    @Test
    fun `secure delete is on`() = runBlocking {
        dao.insert(saved("a", 1000)) // opens the database
        val on = db.useReaderConnection { c -> c.usePrepared("PRAGMA secure_delete") { it.step(); it.getLong(0) } }
        assertEquals(1L, on)
    }
}
