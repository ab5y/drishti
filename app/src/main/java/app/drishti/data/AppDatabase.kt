package app.drishti.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/**
 * Version 1 is the baseline (D53, D63); version 2 adds the consent column (D66).
 * Every schema is exported to app/schemas/. Each change needs a Migration and a migration test.
 */
@Database(entities = [SessionEntity::class, TrialEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v1 sessions keep no consent value: nobody ticked anything for them (D65, D66). */
        private const val ADD_CONSENT = "ALTER TABLE `sessions` ADD COLUMN `consent` INTEGER"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(ADD_CONSENT)

            // Used instead of the above when the database runs on a SQLite driver (the JVM tests).
            override fun migrate(connection: SQLiteConnection) = connection.execSQL(ADD_CONSENT)
        }

        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "drishti.db")
                .addCallback(SecureDelete)
                .addMigrations(*MIGRATIONS)
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
