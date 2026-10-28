package app.drishti.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CsvTest {

    private fun text(s: String?) = Csv.cell(CsvCell.Text(s))

    // ---- escaping (D59) ----------------------------------------------------------------

    @Test
    fun `plain text is written as is`() {
        assertEquals("PASS", text("PASS"))
        assertEquals("6/12", text("6/12"))
        assertEquals("", text(null))
        assertEquals("", text(""))
    }

    @Test
    fun `commas, quotes and newlines are quoted, quotes doubled`() {
        assertEquals("\"a,b\"", text("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", text("say \"hi\""))
        assertEquals("\"line1\nline2\"", text("line1\nline2"))
        assertEquals("\"a\r\nb\"", text("a\r\nb"))
        assertEquals("\"\"\"\"", text("\""))
    }

    @Test
    fun `text that a spreadsheet would run as a formula is defused`() {
        assertEquals("'=1+1", text("=1+1"))
        assertEquals("'+91 98", text("+91 98"))
        assertEquals("'-2", text("-2"))
        assertEquals("'@SUM(A1)", text("@SUM(A1)"))
        assertEquals("'\tx", text("\tx"))
        assertEquals("\"'\rx\"", text("\rx"))
        // Defused and quoted together.
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"", text("=HYPERLINK(\"http://x\",\"y\")"))
        // Only the first character matters.
        assertEquals("a=b", text("a=b"))
    }

    @Test
    fun `numbers are never defused, and use a dot whatever the locale`() {
        assertEquals("-0.1", Csv.cell(CsvCell.Num(-0.1)))
        assertEquals("-0.012", Csv.cell(CsvCell.Num(-0.012)))
        assertEquals("0.0001", Csv.cell(CsvCell.Num(0.0001)))
        assertEquals("15.748031496062993", Csv.cell(CsvCell.Num(400 / 25.4)))
        assertEquals("3", Csv.cell(CsvCell.Num(3)))
        assertEquals("1700000000000", Csv.cell(CsvCell.Num(1_700_000_000_000L)))
        assertEquals("", Csv.cell(CsvCell.Num(null)))
        assertEquals("", Csv.cell(CsvCell.Num(Double.NaN)))
        assertEquals("", Csv.cell(CsvCell.Num(Double.POSITIVE_INFINITY)))
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("2.5", Csv.cell(CsvCell.Num(2.5)))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `booleans and missing values`() {
        assertEquals("true", Csv.cell(CsvCell.Bool(true)))
        assertEquals("false", Csv.cell(CsvCell.Bool(false)))
        assertEquals("", Csv.cell(CsvCell.Bool(null)))
    }

    @Test
    fun `a document has a BOM, a header row and CRLF line ends`() {
        val doc = Csv.document(listOf("a", "b"), listOf(listOf(CsvCell.Text("x,y"), CsvCell.Num(1)), listOf(CsvCell.Text(null), CsvCell.Bool(true))))
        assertEquals("﻿a,b\r\n\"x,y\",1\r\n,true\r\n", doc)
    }

    // ---- the two export files ------------------------------------------------------------

    private fun saved(id: String, schoolId: String? = "SCH-1"): SavedSession {
        val config = TestConfigs.shipped()
        val f = ScreeningFlow(config, 400 / 25.4, FixedDistance.from(config), Random(id.hashCode()), { 0L }, { 200.0 }, 0)
        f.setViewport(2400, 1080)
        f.register(schoolId ?: "", "", 200.0, consent = true)
        repeat(100_000) {
            when (val s = f.screen) {
                is FlowScreen.RemoteCheck -> RemoteCheck.ORDER.forEach { f.onAction(it) }
                is FlowScreen.Prompt -> f.onAction(RemoteAction.OK)
                is FlowScreen.Letter -> f.onAction(RemoteAction.entries[s.trial.orientation.ordinal])
                is FlowScreen.Result -> {
                    val stamp = RecordStamp("0.1.0 (1)", 1, "00", 15.75, "dev", "Clicker", "built-in defaults", "D63")
                    return SessionRecords.build(id, 0, "2026-10-20T10:00:00+05:30", s.result, stamp)
                }
                else -> error("unexpected $s")
            }
        }
        error("did not finish")
    }

    private fun lines(doc: String) = doc.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }

    @Test
    fun `sessions CSV has one row per session, joined to trials by session_id`() {
        val a = saved("s-a")
        val b = saved("s-b", schoolId = null)
        val sessions = lines(SessionCsv.sessions(listOf(a.record, b.record)))
        val header = sessions.first().split(",")
        assertEquals(SessionCsv.SESSION_HEADER, header)
        assertEquals("session_id", header.first())
        assertEquals(header.indexOf("age") + 1, header.indexOf("consent"))
        assertTrue(header.containsAll(listOf("token", "school_id", "overall", "right_logmar", "remote_name", "remote_mapping", "config_sha256", "decisions_rev", "right_remote_drops")))
        assertEquals(3, sessions.size)
        assertTrue(sessions[1].startsWith("s-a,"))
        assertTrue(sessions[2].startsWith("s-b,"))

        val trials = lines(SessionCsv.trials(a.trials + b.trials))
        assertEquals(SessionCsv.TRIAL_HEADER, trials.first().split(","))
        assertEquals(1 + a.trials.size + b.trials.size, trials.size)
        assertEquals(a.trials.size, trials.count { it.startsWith("s-a,") })
        assertEquals(b.trials.size, trials.count { it.startsWith("s-b,") })
    }

    @Test
    fun `every session row has as many cells as the header`() {
        val a = saved("s-a")
        assertEquals(SessionCsv.SESSION_HEADER.size, SessionCsv.sessionCells(a.record).size)
        assertEquals(SessionCsv.TRIAL_HEADER.size, SessionCsv.trialCells(a.trials.first()).size)
    }

    @Test
    fun `negative logMAR and size error stay numbers in the trials file`() {
        val row = saved("s-a").trials.first().copy(logMAR = -0.1, sizeError = -0.012)
        val cells = SessionCsv.trialCells(row).map(Csv::cell)
        assertTrue(cells.toString(), "-0.1" in cells)
        assertTrue(cells.toString(), "-0.012" in cells)
    }

    @Test
    fun `free text in a record is escaped and defused`() {
        val r = saved("s-a").record.copy(schoolId = "=cmd", rightReason = "a, \"b\"\nc")
        val cells = SessionCsv.sessionCells(r).map(Csv::cell)
        assertTrue("'=cmd" in cells)
        assertTrue("\"a, \"\"b\"\"\nc\"" in cells)
    }
}
