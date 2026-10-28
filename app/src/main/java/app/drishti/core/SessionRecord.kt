package app.drishti.core

import java.security.MessageDigest
import kotlin.random.Random

/** The generated session token (D56): six characters from an unambiguous alphabet, shown as "ABC-234". */
object SessionToken {
    /** No 0/O, 1/I/L or U, so it survives being handwritten and read back. */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    fun generate(random: Random): String {
        val chars = List(6) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return chars.take(3).joinToString("") + "-" + chars.drop(3).joinToString("")
    }
}

/** Build and device facts stamped on every record (D54). */
data class RecordStamp(
    val appVersion: String,
    val configSchemaVersion: Int,
    val configSha256: String,
    val pxPerMm: Double,
    val deviceId: String,
    val remoteName: String,
    val remoteMapping: String,
    val decisionsRev: String,
)

/**
 * One saved screening (D53). Flat on purpose: the Room entity embeds it, so
 * every field is a column, and the CSV writes it as one row. Pure Kotlin.
 */
data class SessionRecord(
    val sessionId: String,
    val createdAtMs: Long,
    /** Local time with offset, ISO 8601. */
    val timestamp: String,
    val token: String,
    val schoolId: String?,
    val age: Int?,
    /** The consent tick (D65). Always true for new sessions; null for sessions saved before it existed (D66). */
    val consent: Boolean?,
    val overall: Referral,
    val overallText: String,

    val rightReferral: Referral,
    val rightLogMAR: Double?,
    val rightAcuity: String,
    val rightReason: String,
    val rightResponsesOk: Boolean,
    val rightScored: Int,
    val rightUnsure: Int,
    val rightQuality: String,
    val rightRemoteDrops: Int,
    val rightPinholeRun: Boolean,
    val rightPinholeReferral: Referral?,
    val rightPinholeLogMAR: Double?,
    val rightPinholeImproved: Boolean?,
    val rightPinholeText: String?,
    val rightPinholeRemoteDrops: Int,

    val leftReferral: Referral,
    val leftLogMAR: Double?,
    val leftAcuity: String,
    val leftReason: String,
    val leftResponsesOk: Boolean,
    val leftScored: Int,
    val leftUnsure: Int,
    val leftQuality: String,
    val leftRemoteDrops: Int,
    val leftPinholeRun: Boolean,
    val leftPinholeReferral: Referral?,
    val leftPinholeLogMAR: Double?,
    val leftPinholeImproved: Boolean?,
    val leftPinholeText: String?,
    val leftPinholeRemoteDrops: Int,

    val nearReferral: Referral,
    val nearLogMAR: Double?,
    val nearAcuity: String,
    val nearReason: String,
    val nearResponsesOk: Boolean,
    val nearScored: Int,
    val nearUnsure: Int,
    val nearQuality: String,
    val nearRemoteDrops: Int,

    val distanceOk: Boolean,
    val lightOk: Boolean?,
    /** Far right, far left and near all had OK responses. */
    val responsesOk: Boolean,
    val luxAtRegister: Double?,
    val luxAtTestStart: Double?,
    /** False if the operator switched to on-screen answers. */
    val remoteAnswers: Boolean,

    val appVersion: String,
    val configSchemaVersion: Int,
    val configSha256: String,
    val pxPerMm: Double,
    val deviceId: String,
    val remoteName: String,
    val remoteMapping: String,
    val decisionsRev: String,
    /** Nothing syncs (no networking); kept from build sheet section 08. */
    val synced: Boolean = false,
)

/** One answered letter (D53). */
data class TrialRow(
    val sessionId: String,
    /** Order within the session, from 0. */
    val seq: Int,
    val test: TestKind,
    val eye: Eye,
    val phase: Phase,
    val logMAR: Double,
    val orientation: Orientation,
    val response: Dir,
    val correct: Boolean,
    val unsure: Boolean,
    /** False if the engine voided it (distance lost). */
    val counted: Boolean,
    val distanceMm: Double,
    val intendedMm: Double,
    val drawnMm: Double,
    val strokePx: Int,
    val sizeError: Double,
    val crowded: Boolean,
    val source: AnswerSource,
)

data class SavedSession(val record: SessionRecord, val trials: List<TrialRow>)

object SessionRecords {
    const val QUALITY_SEPARATOR = " | "

    fun build(sessionId: String, createdAtMs: Long, timestamp: String, r: ScreeningResult, stamp: RecordStamp): SavedSession {
        fun drops(test: TestId) = r.remoteDrops[test] ?: 0
        fun pinId(eye: Eye) = TestId(TestKind.PINHOLE, eye)
        fun farId(eye: Eye) = TestId(TestKind.FAR, eye)
        val right = r.right
        val left = r.left
        val near = r.near.result

        val record = SessionRecord(
            sessionId = sessionId,
            createdAtMs = createdAtMs,
            timestamp = timestamp,
            token = r.patient.token,
            schoolId = r.patient.schoolId,
            age = r.patient.age,
            consent = r.patient.consent,
            overall = r.overall,
            overallText = r.overallText,

            rightReferral = right.decision,
            rightLogMAR = right.far.lastPassedLogMAR,
            rightAcuity = Acuity.of(right.far).snellen(),
            rightReason = right.reason,
            rightResponsesOk = right.far.quality.responsesOk,
            rightScored = right.far.quality.scoredResponses,
            rightUnsure = right.far.quality.unsureResponses,
            rightQuality = right.quality.joinToString(QUALITY_SEPARATOR),
            rightRemoteDrops = drops(farId(Eye.RIGHT)),
            rightPinholeRun = right.pinhole != null,
            rightPinholeReferral = right.pinhole?.decision,
            rightPinholeLogMAR = right.pinhole?.lastPassedLogMAR,
            rightPinholeImproved = right.pinholeImproved,
            rightPinholeText = right.pinholeText,
            rightPinholeRemoteDrops = drops(pinId(Eye.RIGHT)),

            leftReferral = left.decision,
            leftLogMAR = left.far.lastPassedLogMAR,
            leftAcuity = Acuity.of(left.far).snellen(),
            leftReason = left.reason,
            leftResponsesOk = left.far.quality.responsesOk,
            leftScored = left.far.quality.scoredResponses,
            leftUnsure = left.far.quality.unsureResponses,
            leftQuality = left.quality.joinToString(QUALITY_SEPARATOR),
            leftRemoteDrops = drops(farId(Eye.LEFT)),
            leftPinholeRun = left.pinhole != null,
            leftPinholeReferral = left.pinhole?.decision,
            leftPinholeLogMAR = left.pinhole?.lastPassedLogMAR,
            leftPinholeImproved = left.pinholeImproved,
            leftPinholeText = left.pinholeText,
            leftPinholeRemoteDrops = drops(pinId(Eye.LEFT)),

            nearReferral = r.near.decision,
            nearLogMAR = near.lastPassedLogMAR,
            nearAcuity = Acuity.of(near).snellen(),
            nearReason = r.near.reason,
            nearResponsesOk = near.quality.responsesOk,
            nearScored = near.quality.scoredResponses,
            nearUnsure = near.quality.unsureResponses,
            nearQuality = r.near.quality.joinToString(QUALITY_SEPARATOR),
            nearRemoteDrops = drops(TestId(TestKind.NEAR, Eye.BOTH)),

            distanceOk = r.distanceOk,
            lightOk = r.lightOk,
            responsesOk = right.far.quality.responsesOk && left.far.quality.responsesOk && near.quality.responsesOk,
            luxAtRegister = r.light.atRegister,
            luxAtTestStart = r.light.atTestStart,
            remoteAnswers = r.remoteAnswers,

            appVersion = stamp.appVersion,
            configSchemaVersion = stamp.configSchemaVersion,
            configSha256 = stamp.configSha256,
            pxPerMm = stamp.pxPerMm,
            deviceId = stamp.deviceId,
            remoteName = stamp.remoteName,
            remoteMapping = stamp.remoteMapping,
            decisionsRev = stamp.decisionsRev,
        )
        val trials = r.trials.mapIndexed { i, t ->
            TrialRow(
                sessionId = sessionId,
                seq = i,
                test = t.test.kind,
                eye = t.test.eye,
                phase = t.phase,
                logMAR = t.logMAR,
                orientation = t.orientation,
                response = t.response,
                correct = t.correct,
                unsure = t.response == Dir.UNSURE,
                counted = t.counted,
                distanceMm = t.distanceMm,
                intendedMm = t.intendedMm,
                drawnMm = t.drawnMm,
                strokePx = t.strokePx,
                sizeError = t.sizeErrorFraction,
                crowded = t.crowded,
                source = t.source,
            )
        }
        return SavedSession(record, trials)
    }
}

/** The remote mapping in use, for the record stamp (D54). */
object RemoteMappingText {
    const val BUILT_IN = "built-in defaults"

    fun describe(profile: RemoteProfile?, label: (InputId) -> String): String {
        val assignments = profile?.assignments.orEmpty()
        if (assignments.isEmpty()) return BUILT_IN
        return assignments.joinToString("; ") { "${label(it.input)}=${it.action}" }
    }
}

object ConfigHash {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
