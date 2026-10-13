package app.drishti.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(record: SessionRecord)

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SessionRecord>>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
