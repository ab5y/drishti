package app.drishti.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.drishti.core.SavedSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Insert
    suspend fun insertTrials(trials: List<TrialEntity>)

    /** A session and its trials, all or nothing. */
    @Transaction
    suspend fun insert(saved: SavedSession) {
        insertSession(SessionEntity(saved.record))
        insertTrials(saved.trials.map(::TrialEntity))
    }

    /** Newest first (D61). */
    @Query("SELECT * FROM sessions ORDER BY createdAtMs DESC, sessionId")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY createdAtMs DESC, sessionId")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE sessionId IN (:ids) ORDER BY createdAtMs DESC, sessionId")
    suspend fun sessions(ids: List<String>): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE sessionId = :id")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM trials ORDER BY sessionId, seq")
    suspend fun allTrials(): List<TrialEntity>

    @Query("SELECT * FROM trials WHERE sessionId IN (:ids) ORDER BY sessionId, seq")
    suspend fun trials(ids: List<String>): List<TrialEntity>

    @Query("SELECT COUNT(*) FROM trials WHERE sessionId = :id")
    suspend fun trialCount(id: String): Int

    @Query("DELETE FROM trials WHERE sessionId = :id")
    suspend fun deleteTrials(id: String)

    @Query("DELETE FROM sessions WHERE sessionId = :id")
    suspend fun deleteSession(id: String): Int

    /**
     * Deletes one session and its trials. The foreign key cascades too; the
     * explicit trial delete keeps it correct even if foreign keys are off.
     */
    @Transaction
    suspend fun delete(id: String): Int {
        deleteTrials(id)
        return deleteSession(id)
    }

    @Query("DELETE FROM trials")
    suspend fun deleteAllTrials()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Transaction
    suspend fun deleteAll() {
        deleteAllTrials()
        deleteAllSessions()
    }
}
