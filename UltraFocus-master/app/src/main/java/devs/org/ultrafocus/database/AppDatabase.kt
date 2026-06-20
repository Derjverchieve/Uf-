package devs.org.ultrafocus.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import devs.org.ultrafocus.extentions.DrawableConverter
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.PauseEvent

@Database(
    entities = [AppInfo::class, FocusSession::class, PauseEvent::class], // Add other entities here
    version = 2,
    exportSchema = false)
@TypeConverters(DrawableConverter::class, Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun pauseEventDao(): PauseEventDao


    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Adds the Deep Work Tracker tables. Written by hand (exportSchema is
        // false, so there's no schema json to generate this from) — kept
        // deliberately explicit so existing AppInfo (blocklist) data survives
        // the upgrade instead of being wiped.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `FocusSession` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `primaryAppPackage` TEXT NOT NULL,
                        `primaryAppName` TEXT NOT NULL,
                        `targetDurationMs` INTEGER NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER,
                        `focusedTimeMs` INTEGER NOT NULL,
                        `pauseTimeMs` INTEGER NOT NULL,
                        `pauseCount` INTEGER NOT NULL,
                        `focusScore` INTEGER,
                        `status` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `PauseEvent` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `startTime` INTEGER NOT NULL,
                        `endTime` INTEGER,
                        `durationMs` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `appPackage` TEXT,
                        `appName` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_PauseEvent_sessionId` ON `PauseEvent` (`sessionId`)"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Safety net only — the real path is MIGRATION_1_2 above.
                    // This just stops a crash if a future version bump ever
                    // ships without its own migration.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
