package app.drishti.data

import android.content.Context
import app.drishti.core.SavedSession
import app.drishti.core.SessionCsv
import app.drishti.core.SessionRecord
import app.drishti.core.TrialRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved screenings (D53) and their CSV export files (D59, D60). Nothing here touches the network. */
class RecordsRepository(context: Context) {
    private val dao = AppDatabase.get(context).sessions()
    private val exportDir = File(context.applicationContext.cacheDir, EXPORT_DIR)

    /** Newest first. */
    val sessions: Flow<List<SessionRecord>> = dao.observeAll().map { list -> list.map { it.record } }

    suspend fun save(saved: SavedSession) = dao.insert(saved)

    suspend fun session(id: String): SessionRecord? = dao.session(id)?.record

    suspend fun trialCount(id: String): Int = dao.trialCount(id)

    suspend fun delete(id: String): Boolean = dao.delete(id) > 0

    suspend fun deleteAll() = dao.deleteAll()

    /**
     * Writes the sessions and trials CSVs for [ids] (all sessions if null) and
     * returns the two files. Earlier export files are deleted first (D60).
     */
    suspend fun export(ids: List<String>?, nowMs: Long): List<File> = withContext(Dispatchers.IO) {
        val (sessions, trials) = if (ids == null) {
            dao.allSessions().map { it.record } to dao.allTrials().map { it.row }
        } else {
            // SQLite on older phones allows at most 999 bound values per query.
            val chunks = ids.distinct().chunked(MAX_IDS_PER_QUERY)
            val s: List<SessionRecord> = chunks.flatMap { c -> dao.sessions(c).map { it.record } }
                .sortedWith(compareByDescending<SessionRecord> { it.createdAtMs }.thenBy { it.sessionId })
            val t: List<TrialRow> = chunks.flatMap { c -> dao.trials(c).map { it.row } }
            s to t
        }
        clearExports()
        exportDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
        val sessionsFile = File(exportDir, "sessions-$stamp.csv").apply { writeText(SessionCsv.sessions(sessions), Charsets.UTF_8) }
        val trialsFile = File(exportDir, "trials-$stamp.csv").apply { writeText(SessionCsv.trials(trials), Charsets.UTF_8) }
        listOf(sessionsFile, trialsFile)
    }

    /** Deletes every earlier export file, so copies of records do not pile up (D60). */
    suspend fun clearExports() = withContext(Dispatchers.IO) {
        exportDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    companion object {
        const val EXPORT_DIR = "exports"
        private const val MAX_IDS_PER_QUERY = 500
    }
}
