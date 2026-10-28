package app.drishti.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Version 1 is the baseline (D53, D63): the schema is exported to app/schemas/.
 * The first schema change must add a Migration and a migration test.
 */
@Database(entities = [SessionEntity::class, TrialEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drishti.db")
                .addCallback(SecureDelete)
                .build()
                .also { instance = it }
        }
    }

    /** Deleted records are overwritten on disk, not left in free pages (D53). */
    object SecureDelete : Callback() {
        private const val PRAGMA = "PRAGMA secure_delete = ON"

        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query(PRAGMA).use { it.moveToFirst() }
        }

        // Used instead of the above when the database runs on a SQLite driver (the JVM tests).
        override fun onOpen(connection: SQLiteConnection) {
            connection.execSQL(PRAGMA)
        }
    }
}
