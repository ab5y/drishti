package app.drishti.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.drishti.core.Referral

/** One screening session (build sheet section 08). Non-PII: a token and optional age, no names. */
@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "token") val token: String,
    @ColumnInfo(name = "age") val age: Int?,
    @ColumnInfo(name = "right_logmar") val rightLogMAR: Double?,
    @ColumnInfo(name = "left_logmar") val leftLogMAR: Double?,
    @ColumnInfo(name = "near_result") val nearResult: String?,
    @ColumnInfo(name = "pinhole_improved") val pinholeImproved: Boolean?,
    @ColumnInfo(name = "right_referral") val rightReferral: Referral,
    @ColumnInfo(name = "left_referral") val leftReferral: Referral,
    @ColumnInfo(name = "overall_referral") val overallReferral: Referral,
    @ColumnInfo(name = "referral_reason") val referralReason: String,
    @ColumnInfo(name = "distance_ok") val distanceOk: Boolean,
    @ColumnInfo(name = "light_ok") val lightOk: Boolean?,
    @ColumnInfo(name = "responses_ok") val responsesOk: Boolean,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "px_per_mm") val pxPerMm: Double,
    @ColumnInfo(name = "timestamp") val timestamp: String,
    @ColumnInfo(name = "synced") val synced: Boolean = false,
)
