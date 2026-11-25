package com.nordic.stepcounter.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepRepositoryTest {

    private lateinit var database: StepDatabase
    private lateinit var preferences: ProfilePreferences
    private lateinit var repository: StepRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        preferences = ProfilePreferences(context)
        preferences.clear()
        repository = StepRepository(database.stepDao(), preferences)
    }

    @After
    fun tearDown() = runTest {
        database.close()
        preferences.clear()
    }

    @Test
    fun firstSensorReading_initializesBaselineWithZeroDeltaAndCreatesInitialRecord() = runTest {
        val record = repository.processHardwareSensorReading(
            rawSensorSteps = 20000L,
            date = "2026-11-10",
            hour = 8
        )

        assertEquals(0, record.steps)
        assertEquals(0f, record.distanceMeters, 0.01f)
        assertEquals(0f, record.caloriesKcal, 0.01f)
        assertEquals(0L, record.activeDurationSeconds)

        val profile = repository.userProfileFlow.first()
        assertEquals(20000L, profile.lastSensorStepValue)
        assertEquals("2026-11-10", profile.lastRecordedDate)

        val savedDaily = repository.getDailyRecordDirect("2026-11-10")
        assertNotNull(savedDaily)
        assertEquals(0, savedDaily?.steps)
    }

    @Test
    fun normalSensorIncrements_updateDailyAndHourlyRecordsCorrectly() = runTest {
        // Initialize baseline at 20000
        repository.processHardwareSensorReading(20000L, "2026-11-10", 8)

        // User walks 150 steps in hour 8
        val r1 = repository.processHardwareSensorReading(20150L, "2026-11-10", 8)
        assertEquals(150, r1.steps)

        // User walks 200 more steps in hour 9
        val r2 = repository.processHardwareSensorReading(20350L, "2026-11-10", 9)
        assertEquals(350, r2.steps)

        val daily = repository.getDailyRecordDirect("2026-11-10")
        assertEquals(350, daily?.steps)

        val hourlyList = repository.getHourlyRecordsFlow("2026-11-10").first()
        assertEquals(2, hourlyList.size)
        assertEquals(8, hourlyList[0].hour)
        assertEquals(150, hourlyList[0].steps)
        assertEquals(9, hourlyList[1].hour)
        assertEquals(200, hourlyList[1].steps)

        val profile = repository.userProfileFlow.first()
        assertEquals(20350L, profile.lastSensorStepValue)
    }

    @Test
    fun midnightRollover_startsNewDayAtZeroStepsAndPreservesBaseline() = runTest {
        // Day 1: User takes 500 steps, ending at hardware reading 20500
        repository.processHardwareSensorReading(20000L, "2026-11-10", 8)
        repository.processHardwareSensorReading(20500L, "2026-11-10", 18)

        val day1Record = repository.getDailyRecordDirect("2026-11-10")
        assertEquals(500, day1Record?.steps)

        // Day 2 (Midnight): First reading at 00:01 with reading 20500 (no new steps yet)
        val day2Initial = repository.processHardwareSensorReading(20500L, "2026-11-11", 0)
        assertEquals(0, day2Initial.steps)
        assertEquals("2026-11-11", day2Initial.date)

        // Verify Day 1 record is untouched
        val day1AfterRollover = repository.getDailyRecordDirect("2026-11-10")
        assertEquals(500, day1AfterRollover?.steps)

        // Baseline preserved and date updated
        val profile = repository.userProfileFlow.first()
        assertEquals(20500L, profile.lastSensorStepValue)
        assertEquals("2026-11-11", profile.lastRecordedDate)

        // Day 2: User walks 75 steps at 07:00
        val day2Walk = repository.processHardwareSensorReading(20575L, "2026-11-11", 7)
        assertEquals(75, day2Walk.steps)
        assertEquals("2026-11-11", day2Walk.date)

        // Hourly record for day 2 exists
        val hourlyDay2 = repository.getHourlyRecordsFlow("2026-11-11").first()
        assertEquals(1, hourlyDay2.size)
        assertEquals(7, hourlyDay2[0].hour)
        assertEquals(75, hourlyDay2[0].steps)
    }

    @Test
    fun midnightRolloverWithSteps_addsDeltaOnlyToNewDay() = runTest {
        // Day 1: ends at 30000 with 1000 steps
        repository.processHardwareSensorReading(29000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(30000L, "2026-11-10", 22)

        // First event of day 2 has 30050 steps taken during midnight transit
        val day2Record = repository.processHardwareSensorReading(30050L, "2026-11-11", 1)
        assertEquals(50, day2Record.steps)
        assertEquals("2026-11-11", day2Record.date)

        val day1Record = repository.getDailyRecordDirect("2026-11-10")
        assertEquals(1000, day1Record?.steps)
    }

    @Test
    fun deviceReboot_handlesCounterResetGracefully() = runTest {
        // Before reboot: baseline 45000 with 200 steps
        repository.processHardwareSensorReading(44800L, "2026-11-10", 12)
        repository.processHardwareSensorReading(45000L, "2026-11-10", 13)

        // Phone reboots: counter resets to 35
        val rebootRecord = repository.processHardwareSensorReading(35L, "2026-11-10", 14)
        assertEquals(235, rebootRecord.steps) // 200 + 35

        val profile = repository.userProfileFlow.first()
        assertEquals(35L, profile.lastSensorStepValue)
    }

    @Test
    fun pauseState_advancesBaselineWithoutIncrementingSteps() = runTest {
        repository.processHardwareSensorReading(1000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(1200L, "2026-11-10", 10) // 200 steps

        repository.setPaused(true)
        val profilePaused = repository.userProfileFlow.first()
        assertTrue(profilePaused.isPaused)

        // Steps taken while paused (vehicle transit)
        val pausedRecord = repository.processHardwareSensorReading(1800L, "2026-11-10", 11)
        assertEquals(200, pausedRecord.steps) // Unchanged!

        val profileAfterPauseWalk = repository.userProfileFlow.first()
        assertEquals(1800L, profileAfterPauseWalk.lastSensorStepValue)

        // User unpauses
        repository.setPaused(false)
        val resumedRecord = repository.processHardwareSensorReading(1850L, "2026-11-10", 12)
        assertEquals(250, resumedRecord.steps) // 200 + 50 (1800 to 1850)
    }

    @Test
    fun updateDailyGoal_updatesPreferencesAndDatabase() = runTest {
        repository.processHardwareSensorReading(1000L, "2026-11-10", 10)
        repository.updateDailyGoal(12000)

        val profile = repository.userProfileFlow.first()
        assertEquals(12000, profile.dailyGoal)

        val today = java.time.LocalDate.now().toString()
        val record = repository.initializeTodayRecord(today)
        repository.updateDailyGoal(15000)
        val updated = repository.getDailyRecordDirect(today)
        assertEquals(15000, updated?.goalSteps)
    }

    @Test
    fun updateDailyGoal_clampsInvalidAndExtremeGoals() = runTest {
        // Clamps below 1,000 to 1,000
        repository.updateDailyGoal(0)
        assertEquals(1000, repository.userProfileFlow.first().dailyGoal)

        repository.updateDailyGoal(-500)
        assertEquals(1000, repository.userProfileFlow.first().dailyGoal)

        // Clamps above 100,000 to 100,000
        repository.updateDailyGoal(250000)
        assertEquals(100000, repository.userProfileFlow.first().dailyGoal)

        // Allows valid goal
        repository.updateDailyGoal(10000)
        assertEquals(10000, repository.userProfileFlow.first().dailyGoal)
    }

    @Test
    fun processHardwareSensorReading_guardsAgainstIntegerOverflow() = runTest {
        // Baseline 0
        repository.processHardwareSensorReading(0L, "2026-11-10", 10)
        // Steps close to Int.MAX_VALUE
        val record = repository.processHardwareSensorReading(Int.MAX_VALUE.toLong() + 5000L, "2026-11-10", 10)
        assertTrue(record.steps > 0)
        assertEquals(Int.MAX_VALUE, record.steps)

        // Additional reading with further steps
        val nextRecord = repository.processHardwareSensorReading(Int.MAX_VALUE.toLong() + 5100L, "2026-11-10", 10)
        assertTrue(nextRecord.steps > 0)
        assertEquals(Int.MAX_VALUE, nextRecord.steps)
    }
}
