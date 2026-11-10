package com.nordic.stepcounter.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StepDaoTest {

    private lateinit var database: StepDatabase
    private lateinit var stepDao: StepDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
}
