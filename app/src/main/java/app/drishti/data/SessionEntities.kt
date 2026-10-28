package app.drishti.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.drishti.core.SessionRecord
import app.drishti.core.TrialRow

/** One screening (D53). Every field of the core record is a column. */
@Entity(tableName = "sessions", primaryKeys = ["sessionId"], indices = [Index("createdAtMs")])
data class SessionEntity(@Embedded val record: SessionRecord)

/** One answered letter, joined to its session; deleted with it (D53). */
@Entity(
    tableName = "trials",
    primaryKeys = ["sessionId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class TrialEntity(@Embedded val row: TrialRow)
