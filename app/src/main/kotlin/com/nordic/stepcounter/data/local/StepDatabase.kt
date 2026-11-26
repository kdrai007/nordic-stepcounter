package com.nordic.stepcounter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DailyStepEntity::class,
        HourlyStepEntity::class,
        SensorStateEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sensor_state` (
                        `id` INTEGER NOT NULL,
                        `lastSensorStepValue` INTEGER NOT NULL,
                        `lastBootCount` INTEGER NOT NULL,
                        `lastRecordedDate` TEXT NOT NULL,
                        `lastEventTimestampMs` INTEGER NOT NULL,
                        `isPaused` INTEGER NOT NULL,
                        `pendingResumeBaseline` INTEGER NOT NULL,
                        `installationId` TEXT NOT NULL,
                        `downtimeGapFromDate` TEXT NOT NULL,
                        `downtimeGapToDate` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun getInstance(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    "nordic_steps.db"
                )
                    // Keep the SQLite file self-contained at rest so Auto Backup never misses WAL changes.
                    .setJournalMode(JournalMode.TRUNCATE)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
