package app.drishti.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * D66: a database file made from the exported v1 schema opens at v2 through
 * the migration, and Room's own schema check passes. JVM SQLite driver (D63).
 */
class AppDatabaseMigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { tmp.newFolder("databases") }

    /** Room asks the Context only where the database file lives. */
    private val context: Context by lazy {
        object : ContextWrapper(null) {
            override fun getDatabasePath(name: String) = File(dir, name)
            override fun getApplicationContext(): Context = this
        }
    }

    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(SCHEMAS, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    /** Creates the v1 tables, indices and Room metadata exactly as 1.json records them, plus one session with one trial. */
    private fun createV1() {
        val db = schema(1)
        assertEquals(1, db["version"]!!.jsonPrimitive.int)
        val conn = BundledSQLiteDriver().open(File(dir, NAME).path)
        try {
            val entities = db["entities"]!!.jsonArray.map { it.jsonObject }
            for (e in entities) {
                val table = e["tableName"]!!.jsonPrimitive.content
                fun sql(o: JsonObject) = o["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                conn.execSQL(sql(e))
                e["indices"]?.jsonArray?.forEach { conn.execSQL(sql(it.jsonObject)) }
            }
            db["setupQueries"]!!.jsonArray.forEach { conn.execSQL(it.jsonPrimitive.content) }
            conn.execSQL("PRAGMA user_version = 1")

            val byName = entities.associateBy { it["tableName"]!!.jsonPrimitive.content }
            insert(conn, byName.getValue("sessions"), V1_SESSION)
            insert(conn, byName.getValue("trials"), V1_TRIAL)
        } finally {
            conn.close()
        }
    }

    /** Fills every column: the given values, NULL where allowed, else a plain value of the column's type. */
    private fun insert(conn: SQLiteConnection, entity: JsonObject, values: Map<String, Any>) {
        val fields = entity["fields"]!!.jsonArray.map { it.jsonObject }
        val names = fields.map { it["columnName"]!!.jsonPrimitive.content }
        val row = fields.map { f ->
            val name = f["columnName"]!!.jsonPrimitive.content
            values[name] ?: if (f["notNull"]?.jsonPrimitive?.boolean != true) null else when (f["affinity"]!!.jsonPrimitive.content) {
                "TEXT" -> "x"
                "INTEGER" -> 0L
                "REAL" -> 0.0
                else -> error("affinity of $name")
            }
        }
        assertTrue("unknown columns in ${values.keys}", names.containsAll(values.keys))
        val table = entity["tableName"]!!.jsonPrimitive.content
        val sql = "INSERT INTO `$table` (${names.joinToString { "`$it`" }}) VALUES (${names.joinToString { "?" }})"
        conn.prepare(sql).use { st ->
            row.forEachIndexed { i, v ->
                when (v) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindText(i + 1, v)
                    is Long -> st.bindLong(i + 1, v)
                    is Int -> st.bindLong(i + 1, v.toLong())
                    is Double -> st.bindDouble(i + 1, v)
                    else -> error("value $v")
                }
            }
            st.step()
        }
    }

    private fun open(withMigration: Boolean = true): AppDatabase {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
        if (withMigration) builder.addMigrations(*AppDatabase.MIGRATIONS)
        return builder.build()
    }

    @Test
    fun `a v1 database migrates to v2 and keeps its sessions, with no consent value`() = runBlocking {
        createV1()
        val db = open()
        try {
            val dao = db.sessions()
            val r = dao.session("old")!!.record
            assertEquals("OLD-234", r.token)
            assertEquals("SCH-9", r.schoolId)
            assertEquals(1000L, r.createdAtMs)
            assertNull("nobody ticked consent for a v1 session (D65)", r.consent)
            assertEquals(1, dao.trialCount("old"))
            val version = db.useReaderConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } }
            assertEquals(2L, version)
        } finally {
            db.close()
        }
    }

    @Test
    fun `without the migration a v1 database does not open`() {
        createV1()
        val db = open(withMigration = false)
        try {
            val failure = runCatching { runBlocking { db.sessions().session("old") } }.exceptionOrNull()
            assertTrue(
                "expected a missing-migration error, got $failure",
                failure is IllegalStateException && failure.message.orEmpty().contains("from 1 to 2"),
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the exported schemas are v1 and v2, and only consent was added`() {
        fun columns(version: Int, table: String) = schema(version)["entities"]!!.jsonArray.map { it.jsonObject }
            .single { it["tableName"]!!.jsonPrimitive.content == table }["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(2, schema(2)["version"]!!.jsonPrimitive.int)
        assertEquals(listOf("consent"), columns(2, "sessions") - columns(1, "sessions").toSet())
        assertEquals(emptyList<String>(), columns(1, "sessions") - columns(2, "sessions").toSet())
        assertEquals(columns(1, "trials"), columns(2, "trials"))
    }

    companion object {
        // Unit tests run with the module directory as the working directory.
        private val SCHEMAS = File("schemas/app.drishti.data.AppDatabase")
        private const val NAME = "drishti.db"

        private val V1_SESSION = mapOf(
            "sessionId" to "old", "createdAtMs" to 1000L, "token" to "OLD-234", "schoolId" to "SCH-9",
            "overall" to "PASS", "rightReferral" to "PASS", "leftReferral" to "PASS", "nearReferral" to "PASS",
        )
        private val V1_TRIAL = mapOf(
            "sessionId" to "old", "seq" to 0L, "test" to "FAR", "eye" to "RIGHT", "phase" to "DESCENDING_LINES",
            "orientation" to "LEFT", "response" to "UNSURE", "source" to "PAD",
        )
    }
}
