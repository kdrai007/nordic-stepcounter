package com.nordic.stepcounter.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StepDaoTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: StepDatabase
    private lateinit var stepDao: StepDao

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stepDao = database.stepDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testInsertAndGetDailySteps() = runTest {
        val daily = DailyStepEntity(
            date = "2026-11-10",
            steps = 8450,
            distanceMeters = 6337.5f,
            caloriesKcal = 422.5f,
            activeDurationSeconds = 5070L,
            goalSteps = 10000
        )
        stepDao.upsertDaily(daily)

        val retrievedFlow = stepDao.getDailyRecord("2026-11-10").first()
        assertNotNull(retrievedFlow)
        assertEquals("2026-11-10", retrievedFlow?.date)
        assertEquals(8450, retrievedFlow?.steps)
        assertEquals(6337.5f, retrievedFlow?.distanceMeters ?: 0f, 0.01f)
        assertEquals(422.5f, retrievedFlow?.caloriesKcal ?: 0f, 0.01f)
        assertEquals(5070L, retrievedFlow?.activeDurationSeconds)
        assertEquals(10000, retrievedFlow?.goalSteps)

        val retrievedDirect = stepDao.getDailyRecordDirect("2026-11-10")
        assertEquals(daily, retrievedDirect)
    }

    @Test
    fun testHourlyStepBucketing() = runTest {
        val h14 = HourlyStepEntity(date = "2026-11-10", hour = 14, steps = 1200)
        val h8 = HourlyStepEntity(date = "2026-11-10", hour = 8, steps = 800)
        val h10 = HourlyStepEntity(date = "2026-11-10", hour = 10, steps = 1500)
        val hOtherDate = HourlyStepEntity(date = "2026-11-09", hour = 8, steps = 500)

        stepDao.upsertHourly(h14)
        stepDao.upsertHourly(h8)
        stepDao.upsertHourly(h10)
        stepDao.upsertHourly(hOtherDate)

        val records = stepDao.getHourlyRecordsForDate("2026-11-10").first()
        assertEquals(3, records.size)
        assertEquals(8, records[0].hour)
        assertEquals(800, records[0].steps)
        assertEquals(10, records[1].hour)
        assertEquals(1500, records[1].steps)
        assertEquals(14, records[2].hour)
        assertEquals(1200, records[2].steps)

        val directRecord = stepDao.getHourlyRecordDirect("2026-11-10", 14)
        assertEquals(1200, directRecord?.steps)

        val nonExistent = stepDao.getHourlyRecordDirect("2026-11-10", 3)
        assertNull(nonExistent)
    }

    @Test
    fun testUpdateDailyGoal() = runTest {
        val daily = DailyStepEntity(
            date = "2026-11-10",
            steps = 5000,
            distanceMeters = 3500f,
            caloriesKcal = 250f,
            activeDurationSeconds = 3000L,
            goalSteps = 8000
        )
        stepDao.upsertDaily(daily)

        val updatedRows = stepDao.updateDailyGoal(date = "2026-11-10", goal = 12000)
        assertEquals(1, updatedRows)

        val updatedRecord = stepDao.getDailyRecordDirect("2026-11-10")
        assertEquals(12000, updatedRecord?.goalSteps)
        assertEquals(5000, updatedRecord?.steps)
    }

    @Test
    fun testGetRecentDailyRecords() = runTest {
        val d1 = DailyStepEntity("2026-11-08", 4000, 3000f, 200f, 2400L, 10000)
        val d2 = DailyStepEntity("2026-11-09", 7000, 5000f, 350f, 4200L, 10000)
        val d3 = DailyStepEntity("2026-11-10", 9000, 6700f, 450f, 5400L, 10000)

        stepDao.upsertDaily(d1)
        stepDao.upsertDaily(d3)
        stepDao.upsertDaily(d2)

        val recentTwo = stepDao.getRecentDailyRecords(limit = 2).first()
        assertEquals(2, recentTwo.size)
        assertEquals("2026-11-10", recentTwo[0].date)
        assertEquals("2026-11-09", recentTwo[1].date)

        val recentAll = stepDao.getRecentDailyRecords(limit = 10).first()
        assertEquals(3, recentAll.size)
        assertEquals("2026-11-10", recentAll[0].date)
        assertEquals("2026-11-09", recentAll[1].date)
        assertEquals("2026-11-08", recentAll[2].date)
    }

    @Test
    fun testRangeBestDayAndEarliestDateQueriesIgnoreFuturePollution() = runTest {
        val oldBest = DailyStepEntity("2026-09-01", 18000, 13000f, 900f, 10800L, 10000)
        val inRange = DailyStepEntity("2026-11-08", 4000, 3000f, 200f, 2400L, 10000)
        val today = DailyStepEntity("2026-11-10", 7000, 5000f, 350f, 4200L, 10000)
        val futureBogus = DailyStepEntity("2027-01-01", 99000, 70000f, 5000f, 59400L, 10000)

        stepDao.upsertDaily(oldBest)
        stepDao.upsertDaily(inRange)
        stepDao.upsertDaily(today)
        stepDao.upsertDaily(futureBogus)

        val rangeRecords = stepDao.getDailyRecordsInRange("2026-10-12", "2026-11-10").first()
        assertEquals(listOf("2026-11-10", "2026-11-08"), rangeRecords.map { it.date })

        val bestDay = stepDao.getBestDaySteps("2026-11-10").first()
        assertEquals(18000, bestDay)

        val earliest = stepDao.getEarliestRecordDate("2026-11-10").first()
        assertEquals("2026-09-01", earliest)
    }

    @Test
    fun testGetTotalLifetimeSteps() = runTest {
        val d1 = DailyStepEntity("2026-11-08", 4000, 3000f, 200f, 2400L, 10000)
        val d2 = DailyStepEntity("2026-11-09", 7000, 5000f, 350f, 4200L, 10000)
        val d3 = DailyStepEntity("2026-11-10", 9000, 6700f, 450f, 5400L, 10000)

        stepDao.upsertDaily(d1)
        stepDao.upsertDaily(d2)
        stepDao.upsertDaily(d3)

        val totalSteps = stepDao.getTotalLifetimeSteps()
        assertEquals(20000L, totalSteps)
    }

    @Test
    fun testUpsertDailyAndHourlyAtomic() = runTest {
        val daily = DailyStepEntity(
            date = "2026-11-12",
            steps = 2500,
            distanceMeters = 1800f,
            caloriesKcal = 120f,
            activeDurationSeconds = 1500L,
            goalSteps = 8000
        )
        val hourly = HourlyStepEntity(
            date = "2026-11-12",
            hour = 10,
            steps = 2500
        )

        stepDao.upsertDailyAndHourly(daily, hourly)

        assertEquals(daily, stepDao.getDailyRecordDirect("2026-11-12"))
        assertEquals(hourly, stepDao.getHourlyRecordDirect("2026-11-12", 10))

        val dailyUpdate = daily.copy(steps = 2600)
        stepDao.upsertDailyAndHourly(dailyUpdate, null)
        assertEquals(2600, stepDao.getDailyRecordDirect("2026-11-12")?.steps)
    }

    @Test
    fun testPersistSensorUpdateAtomicallyUpdatesAllTables() = runTest {
        val sensorState = SensorStateEntity(
            lastSensorStepValue = 12345L,
            lastBootCount = 4,
            lastRecordedDate = "2026-11-15",
            lastEventTimestampMs = 1_700_000_000_000L,
            isPaused = false,
            pendingResumeBaseline = false,
            installationId = "install-abc"
        )
        val daily = DailyStepEntity("2026-11-15", 450, 320f, 15f, 270L, 6000)
        val hourly = HourlyStepEntity("2026-11-15", 11, 450)

        stepDao.persistSensorUpdate(sensorState, daily, hourly)

        assertEquals(sensorState, stepDao.getSensorStateDirect())
        assertEquals(sensorState, stepDao.getSensorStateFlow().first())
        assertEquals(daily, stepDao.getDailyRecordDirect("2026-11-15"))
        assertEquals(hourly, stepDao.getHourlyRecordDirect("2026-11-15", 11))
    }

    @Test
    fun testMigrationFromV1ToV2PreservesHistoryAndCreatesSensorStateTable() = runTest {
        val dbFile = tempFolder.newFile("migration_test.db")

        // Create a real v1 database file matching StepDatabase v1 schema
        val v1OpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `daily_steps` (`date` TEXT NOT NULL, `steps` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, `caloriesKcal` REAL NOT NULL, `activeDurationSeconds` INTEGER NOT NULL, `goalSteps` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `hourly_steps` (`date` TEXT NOT NULL, `hour` INTEGER NOT NULL, `steps` INTEGER NOT NULL, PRIMARY KEY(`date`, `hour`))"
                        )
                        db.execSQL(
                            "INSERT INTO `daily_steps` VALUES ('2026-11-10', 8100, 6000.0, 260.0, 4860, 8000)"
                        )
                        db.execSQL(
                            "INSERT INTO `hourly_steps` VALUES ('2026-11-10', 9, 3100)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        v1OpenHelper.writableDatabase.close()
        v1OpenHelper.close()

        // Open with Room v2 + MIGRATION_1_2 (Room validates exact schema on open)
        val migratedDb = Room.databaseBuilder(context, StepDatabase::class.java, dbFile.absolutePath)
            .addMigrations(StepDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        try {
            val migratedDao = migratedDb.stepDao()
            val preservedDaily = migratedDao.getDailyRecordDirect("2026-11-10")
            assertNotNull(preservedDaily)
            assertEquals(8100, preservedDaily?.steps)

            val preservedHourly = migratedDao.getHourlyRecordDirect("2026-11-10", 9)
            assertEquals(3100, preservedHourly?.steps)

            val newSensorState = SensorStateEntity(lastSensorStepValue = 999L, lastBootCount = 2)
            migratedDao.upsertSensorState(newSensorState)
            assertEquals(999L, migratedDao.getSensorStateDirect()?.lastSensorStepValue)
        } finally {
            migratedDb.close()
        }
    }
}
