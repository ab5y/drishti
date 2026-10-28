package app.drishti.core

import java.math.BigDecimal

/** One CSV cell. Only text is ever defused; numbers stay numbers (D59). */
sealed interface CsvCell {
    data class Text(val value: String?) : CsvCell
    data class Num(val value: Number?) : CsvCell
    data class Bool(val value: Boolean?) : CsvCell
}

/** RFC 4180 CSV with formula-injection defusing (D59). Pure Kotlin. */
object Csv {
    private const val BOM = "﻿"
    private const val EOL = "\r\n"
    private val FORMULA_START = setOf('=', '+', '-', '@', '\t', '\r')

    fun cell(c: CsvCell): String = when (c) {
        is CsvCell.Text -> text(c.value)
        is CsvCell.Num -> number(c.value)
        is CsvCell.Bool -> c.value?.toString().orEmpty()
    }

    fun document(header: List<String>, rows: List<List<CsvCell>>): String = buildString {
        append(BOM)
        append(header.joinToString(",") { text(it) }).append(EOL)
        rows.forEach { row -> append(row.joinToString(",") { cell(it) }).append(EOL) }
    }

    private fun text(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        // A spreadsheet runs a cell starting with these as a formula.
        val safe = if (value.first() in FORMULA_START) "'$value" else value
        val needsQuotes = safe.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private fun number(n: Number?): String = when (n) {
        null -> ""
        is Double -> if (n.isFinite()) plain(n) else ""
        is Float -> if (n.isFinite()) plain(n.toDouble()) else ""
        else -> n.toString()
    }

    /** No exponent, no locale: "0.0001", "-0.1", "3". */
    private fun plain(d: Double): String = BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
}

/** The two export files, joined by session_id (D59). */
object SessionCsv {
    private fun t(s: String?) = CsvCell.Text(s)
    private fun n(v: Number?) = CsvCell.Num(v)
    private fun b(v: Boolean?) = CsvCell.Bool(v)
    private fun e(v: Enum<*>?) = CsvCell.Text(v?.name)

    private val SESSION_COLUMNS: List<Pair<String, (SessionRecord) -> CsvCell>> = listOf<Pair<String, (SessionRecord) -> CsvCell>>(
        "session_id" to { r -> t(r.sessionId) },
        "timestamp" to { r -> t(r.timestamp) },
        "created_at_epoch_ms" to { r -> n(r.createdAtMs) },
        "token" to { r -> t(r.token) },
        "school_id" to { r -> t(r.schoolId) },
        "age" to { r -> n(r.age) },
        "consent" to { r -> b(r.consent) },
        "overall" to { r -> e(r.overall) },
        "overall_text" to { r -> t(r.overallText) },
    ) + eye("right", { it.rightReferral }, { it.rightLogMAR }, { it.rightAcuity }, { it.rightReason }, { it.rightResponsesOk },
        { it.rightScored }, { it.rightUnsure }, { it.rightQuality }, { it.rightRemoteDrops }) +
        pinhole("right", { it.rightPinholeRun }, { it.rightPinholeReferral }, { it.rightPinholeLogMAR }, { it.rightPinholeImproved },
            { it.rightPinholeText }, { it.rightPinholeRemoteDrops }) +
        eye("left", { it.leftReferral }, { it.leftLogMAR }, { it.leftAcuity }, { it.leftReason }, { it.leftResponsesOk },
            { it.leftScored }, { it.leftUnsure }, { it.leftQuality }, { it.leftRemoteDrops }) +
        pinhole("left", { it.leftPinholeRun }, { it.leftPinholeReferral }, { it.leftPinholeLogMAR }, { it.leftPinholeImproved },
            { it.leftPinholeText }, { it.leftPinholeRemoteDrops }) +
        eye("near", { it.nearReferral }, { it.nearLogMAR }, { it.nearAcuity }, { it.nearReason }, { it.nearResponsesOk },
            { it.nearScored }, { it.nearUnsure }, { it.nearQuality }, { it.nearRemoteDrops }) +
        listOf<Pair<String, (SessionRecord) -> CsvCell>>(
            "distance_ok" to { r -> b(r.distanceOk) },
            "light_ok" to { r -> b(r.lightOk) },
            "responses_ok" to { r -> b(r.responsesOk) },
            "lux_at_register" to { r -> n(r.luxAtRegister) },
            "lux_at_test_start" to { r -> n(r.luxAtTestStart) },
            "answers" to { r -> t(if (r.remoteAnswers) "remote" else "on-screen") },
            "app_version" to { r -> t(r.appVersion) },
            "config_schema_version" to { r -> n(r.configSchemaVersion) },
            "config_sha256" to { r -> t(r.configSha256) },
            "px_per_mm" to { r -> n(r.pxPerMm) },
            "device_id" to { r -> t(r.deviceId) },
            "remote_name" to { r -> t(r.remoteName) },
            "remote_mapping" to { r -> t(r.remoteMapping) },
            "decisions_rev" to { r -> t(r.decisionsRev) },
            "synced" to { r -> b(r.synced) },
        )

    private fun eye(
        p: String,
        referral: (SessionRecord) -> Referral,
        logMAR: (SessionRecord) -> Double?,
        acuity: (SessionRecord) -> String,
        reason: (SessionRecord) -> String,
        responsesOk: (SessionRecord) -> Boolean,
        scored: (SessionRecord) -> Int,
        unsure: (SessionRecord) -> Int,
        quality: (SessionRecord) -> String,
        drops: (SessionRecord) -> Int,
    ): List<Pair<String, (SessionRecord) -> CsvCell>> = listOf(
        "${p}_referral" to { r -> e(referral(r)) },
        "${p}_logmar" to { r -> n(logMAR(r)) },
        "${p}_acuity" to { r -> t(acuity(r)) },
        "${p}_reason" to { r -> t(reason(r)) },
        "${p}_responses_ok" to { r -> b(responsesOk(r)) },
        "${p}_scored" to { r -> n(scored(r)) },
        "${p}_unsure" to { r -> n(unsure(r)) },
        "${p}_quality" to { r -> t(quality(r)) },
        "${p}_remote_drops" to { r -> n(drops(r)) },
    )

    private fun pinhole(
        p: String,
        run: (SessionRecord) -> Boolean,
        referral: (SessionRecord) -> Referral?,
        logMAR: (SessionRecord) -> Double?,
        improved: (SessionRecord) -> Boolean?,
        text: (SessionRecord) -> String?,
        drops: (SessionRecord) -> Int,
    ): List<Pair<String, (SessionRecord) -> CsvCell>> = listOf(
        "${p}_pinhole_run" to { r -> b(run(r)) },
        "${p}_pinhole_referral" to { r -> e(referral(r)) },
        "${p}_pinhole_logmar" to { r -> n(logMAR(r)) },
        "${p}_pinhole_improved" to { r -> b(improved(r)) },
        "${p}_pinhole_text" to { r -> t(text(r)) },
        "${p}_pinhole_remote_drops" to { r -> n(drops(r)) },
    )

    private val TRIAL_COLUMNS: List<Pair<String, (TrialRow) -> CsvCell>> = listOf(
        "session_id" to { r -> t(r.sessionId) },
        "seq" to { r -> n(r.seq) },
        "test" to { r -> e(r.test) },
        "eye" to { r -> e(r.eye) },
        "phase" to { r -> e(r.phase) },
        "logmar" to { r -> n(r.logMAR) },
        "orientation" to { r -> e(r.orientation) },
        "response" to { r -> e(r.response) },
        "correct" to { r -> b(r.correct) },
        "unsure" to { r -> b(r.unsure) },
        "counted" to { r -> b(r.counted) },
        "distance_mm" to { r -> n(r.distanceMm) },
        "intended_mm" to { r -> n(r.intendedMm) },
        "drawn_mm" to { r -> n(r.drawnMm) },
        "stroke_px" to { r -> n(r.strokePx) },
        "size_error" to { r -> n(r.sizeError) },
        "crowded" to { r -> b(r.crowded) },
        "source" to { r -> t(r.source.name.lowercase()) },
    )

    val SESSION_HEADER: List<String> = SESSION_COLUMNS.map { it.first }
    val TRIAL_HEADER: List<String> = TRIAL_COLUMNS.map { it.first }

    fun sessionCells(r: SessionRecord): List<CsvCell> = SESSION_COLUMNS.map { it.second(r) }
    fun trialCells(r: TrialRow): List<CsvCell> = TRIAL_COLUMNS.map { it.second(r) }

    fun sessions(records: List<SessionRecord>): String = Csv.document(SESSION_HEADER, records.map(::sessionCells))
    fun trials(rows: List<TrialRow>): String = Csv.document(TRIAL_HEADER, rows.map(::trialCells))
}
