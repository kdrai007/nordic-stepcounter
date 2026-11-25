package com.nordic.stepcounter.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StepRepositoryTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var database: StepDatabase
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var preferences: ProfilePreferences
    private lateinit var repository: StepRepository

    private var fakeBootCount = 10
    private var fakeInstallationId = "device-a"
    private var fakeNowMillis = 1_700_000_000_000L
    private var fakeElapsedNanos = 10_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dataStoreScope = TestScope(UnconfinedTestDispatcher())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { tempFolder.newFile("repo_prefs_${UUID.randomUUID()}.preferences_pb") }
        )
        preferences = ProfilePreferences(dataStore)
        repository = createRepository()
    }

    private fun createRepository(): StepRepository {
        return StepRepository(
            stepDao = database.stepDao(),
            preferences = preferences,
            bootCountProvider = { fakeBootCount },
            installationIdProvider = { fakeInstallationId },
            currentTimeMillisProvider = { fakeNowMillis },
            elapsedRealtimeNanosProvider = { fakeElapsedNanos },
            zoneIdProvider = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        database.close()
        StepRepository.resetInstance()
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

        val sensorState = repository.getSensorStateDirect()
        assertNotNull(sensorState)
        assertEquals(20000L, sensorState?.lastSensorStepValue)
        assertEquals(10, sensorState?.lastBootCount)
        assertEquals("2026-11-10", sensorState?.lastRecordedDate)

        val savedDaily = repository.getDailyRecordDirect("2026-11-10")
        assertNotNull(savedDaily)
        assertEquals(0, savedDaily?.steps)
    }

    @Test
    fun normalSensorIncrements_updateDailyAndHourlyRecordsCorrectly() = runTest {
        repository.processHardwareSensorReading(20000L, "2026-11-10", 8)

        val r1 = repository.processHardwareSensorReading(20150L, "2026-11-10", 8)
        assertEquals(150, r1.steps)

        val r2 = repository.processHardwareSensorReading(20350L, "2026-11-10", 9)
        assertEquals(350, r2.steps)
        // 350 * 60 / 100 = 210s
        assertEquals(210L, r2.activeDurationSeconds)

        val daily = repository.getDailyRecordDirect("2026-11-10")
        assertEquals(350, daily?.steps)

        val hourlyList = repository.getHourlyRecordsFlow("2026-11-10").first()
        assertEquals(2, hourlyList.size)
        assertEquals(8, hourlyList[0].hour)
        assertEquals(150, hourlyList[0].steps)
        assertEquals(9, hourlyList[1].hour)
        assertEquals(200, hourlyList[1].steps)

        assertEquals(20350L, repository.getSensorStateDirect()?.lastSensorStepValue)
    }

    @Test
    fun eventTimestamp_attributesBufferedStepsAcrossMidnightToActualOccurrenceDayAndHour() = runTest {
        // Current wall clock is 2026-11-11 00:05:00 UTC, elapsedRealtimeNanos = 1_000_000_000_000L (1000s)
        val nowZdt = ZonedDateTime.of(2026, 11, 11, 0, 5, 0, 0, ZoneOffset.UTC)
        fakeNowMillis = nowZdt.toInstant().toEpochMilli()
        fakeElapsedNanos = 1_000_000_000_000L

        // Baseline event at 23:50 on 2026-11-10 (15 minutes = 900s before now)
        val baselineEventNanos = fakeElapsedNanos - 900_000_000_000L
        repository.processHardwareSensorReading(
            rawSensorSteps = 5000L,
            eventTimestampNanos = baselineEventNanos
        )

        // Buffered batch event occurred at 23:55 on 2026-11-10 (10 minutes = 600s before now), delivered at 00:05
        val yesterdayLateEventNanos = fakeElapsedNanos - 600_000_000_000L
        val record = repository.processHardwareSensorReading(
            rawSensorSteps = 5120L,
            eventTimestampNanos = yesterdayLateEventNanos
        )

        assertEquals("2026-11-10", record.date)
        assertEquals(120, record.steps)
        val hourlyYesterday = repository.getHourlyRecordsFlow("2026-11-10").first()
        assertEquals(1, hourlyYesterday.size)
        assertEquals(23, hourlyYesterday[0].hour)
        assertEquals(120, hourlyYesterday[0].steps)
    }

    @Test
    fun midnightRollover_startsNewDayAtZeroStepsAndPreservesBaseline() = runTest {
        repository.processHardwareSensorReading(20000L, "2026-11-10", 8)
        repository.processHardwareSensorReading(20500L, "2026-11-10", 18)

        assertEquals(500, repository.getDailyRecordDirect("2026-11-10")?.steps)

        val day2Initial = repository.processHardwareSensorReading(20500L, "2026-11-11", 0)
        assertEquals(0, day2Initial.steps)
        assertEquals("2026-11-11", day2Initial.date)
        assertEquals(500, repository.getDailyRecordDirect("2026-11-10")?.steps)

        val sensorState = repository.getSensorStateDirect()
        assertEquals(20500L, sensorState?.lastSensorStepValue)
        assertEquals("2026-11-11", sensorState?.lastRecordedDate)

        val day2Walk = repository.processHardwareSensorReading(20575L, "2026-11-11", 7)
        assertEquals(75, day2Walk.steps)
        assertEquals(75, repository.getHourlyRecordsFlow("2026-11-11").first()[0].steps)
    }

    @Test
    fun midnightRolloverWithSteps_addsDeltaOnlyToNewDay() = runTest {
        repository.processHardwareSensorReading(29000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(30000L, "2026-11-10", 22)

        val day2Record = repository.processHardwareSensorReading(30050L, "2026-11-11", 1)
        assertEquals(50, day2Record.steps)
        assertEquals("2026-11-11", day2Record.date)
        assertEquals(1000, repository.getDailyRecordDirect("2026-11-10")?.steps)
    }

    @Test
    fun deviceReboot_withBootCountChangeCountsStepsEvenWhenExceedingOldBaseline() = runTest {
        fakeBootCount = 5
        repository.processHardwareSensorReading(100L, "2026-11-10", 12)
        repository.processHardwareSensorReading(150L, "2026-11-10", 13) // +50 steps, baseline = 150

        // Reboot increments boot count to 6; user walks 300 steps (> 150!) before service starts
        fakeBootCount = 6
        val rebootRecord = repository.processHardwareSensorReading(300L, "2026-11-10", 14)
        assertEquals(350, rebootRecord.steps) // 50 + 300
        assertEquals(300L, repository.getSensorStateDirect()?.lastSensorStepValue)
        assertEquals(6, repository.getSensorStateDirect()?.lastBootCount)
    }

    @Test
    fun sameBootCountRegression_isIgnoredInsteadOfCausingPhantomSpike() = runTest {
        fakeBootCount = 7
        repository.processHardwareSensorReading(10000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(10080L, "2026-11-10", 10) // 80 steps

        // Stale lower reading in same boot session must NOT trigger fake reboot +10050
        val afterStale = repository.processHardwareSensorReading(10050L, "2026-11-10", 10)
        assertEquals(80, afterStale.steps)
        assertEquals(10080L, repository.getSensorStateDirect()?.lastSensorStepValue)
    }

    @Test
    fun multiDayDowntimeGap_resetsBaselineAndRecordsDismissibleNotice() = runTest {
        repository.processHardwareSensorReading(10000L, "2026-11-10", 18)
        repository.processHardwareSensorReading(10500L, "2026-11-10", 19)

        // Service dead for >1 day (Nov 11 missed), resumes on Nov 12 with 25,000 steps
        val wedRecord = repository.processHardwareSensorReading(25000L, "2026-11-12", 8)
        assertEquals(0, wedRecord.steps) // Multi-day offline steps not dumped into Wednesday

        val state = repository.getSensorStateDirect()
        assertEquals(25000L, state?.lastSensorStepValue)
        assertEquals("2026-11-10", state?.downtimeGapFromDate)
        assertEquals("2026-11-12", state?.downtimeGapToDate)

        // Subsequent steps on Nov 12 count normally from 25000
        val laterWed = repository.processHardwareSensorReading(25100L, "2026-11-12", 9)
        assertEquals(100, laterWed.steps)

        repository.dismissDowntimeNotice()
        val cleared = repository.getSensorStateDirect()
        assertEquals("", cleared?.downtimeGapFromDate)
        assertEquals("", cleared?.downtimeGapToDate)
    }

    @Test
    fun crossDeviceBackupRestore_resetsHardwareBaselineWithoutPhantomSteps() = runTest {
        fakeInstallationId = "phone-a"
        fakeBootCount = 12
        repository.processHardwareSensorReading(40000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(40200L, "2026-11-10", 11)

        // Restored onto Phone B (different non-backed-up installationId), same day or next day
        fakeInstallationId = "phone-b"
        fakeBootCount = 3
        val restoredFirst = repository.processHardwareSensorReading(15000L, "2026-11-11", 9)
        assertEquals(0, restoredFirst.steps)
        assertEquals(200, repository.getDailyRecordDirect("2026-11-10")?.steps)
        assertEquals("phone-b", repository.getSensorStateDirect()?.installationId)
        assertEquals(15000L, repository.getSensorStateDirect()?.lastSensorStepValue)
    }

    @Test
    fun legacyDataStoreSensorState_migratesIntoRoomOnInitialization() = runTest {
        dataStore.edit { prefs ->
            prefs[longPreferencesKey("key_last_sensor_step_value")] = 12000L
            prefs[stringPreferencesKey("key_last_recorded_date")] = "2026-11-10"
            prefs[booleanPreferencesKey("key_is_paused")] = true
        }

        repository.initializeTodayRecord("2026-11-10")
        val state = repository.getSensorStateDirect()
        assertNotNull(state)
        assertEquals(12000L, state?.lastSensorStepValue)
        assertEquals("2026-11-10", state?.lastRecordedDate)
        assertTrue(state?.isPaused == true)
        assertTrue(repository.isPausedFlow.first())
    }

    @Test
    fun pauseStateAndPendingResumeBaseline_preventCountingPausedSteps() = runTest {
        repository.processHardwareSensorReading(1000L, "2026-11-10", 10)
        repository.processHardwareSensorReading(1200L, "2026-11-10", 10) // 200 steps

        repository.setPaused(true)
        assertTrue(repository.isPausedFlow.first())

        val pausedRecord = repository.processHardwareSensorReading(1800L, "2026-11-10", 11)
        assertEquals(200, pausedRecord.steps)
        assertEquals(1800L, repository.getSensorStateDirect()?.lastSensorStepValue)

        // Simulate cold-start resume where sensor hasn't reported yet (pendingResumeBaseline = true)
        repository.setPaused(false, pendingResumeBaseline = true)
        val coldStartReading = repository.processHardwareSensorReading(2100L, "2026-11-10", 12)
        assertEquals(200, coldStartReading.steps) // 300 paused steps absorbed into baseline

        val resumedRecord = repository.processHardwareSensorReading(2150L, "2026-11-10", 12)
        assertEquals(250, resumedRecord.steps)
    }

    @Test
    fun updateDailyGoal_updatesPreferencesAndDatabaseAndClampsExtremes() = runTest {
        repository.updateDailyGoal(0)
        assertEquals(1000, repository.userProfileFlow.first().dailyGoal)

        repository.updateDailyGoal(250000)
        assertEquals(100000, repository.userProfileFlow.first().dailyGoal)

        val today = LocalDate.now(ZoneOffset.UTC).toString()
        repository.initializeTodayRecord(today)
        repository.updateDailyGoal(15000)
        assertEquals(15000, repository.getDailyRecordDirect(today)?.goalSteps)
    }

    @Test
    fun updatePhysicalProfile_sanitizesInvalidInputsAndRecalculatesTodayMetrics() = runTest {
        val today = LocalDate.now(ZoneOffset.UTC).toString()
        database.stepDao().upsertDaily(
            DailyStepEntity(
                date = today,
                steps = 10000,
                distanceMeters = 7000f,
                caloriesKcal = 300f,
                activeDurationSeconds = 6000L,
                goalSteps = 10000
            )
        )

        // NaN / out-of-range values sanitized
        repository.updatePhysicalProfile(
            heightCm = Float.NaN,
            weightKg = 80f,
            isMale = true,
            customStrideLengthMeters = 0.80f
        )

        val profile = repository.userProfileFlow.first()
        assertEquals(175f, profile.heightCm, 0.01f)
        assertEquals(80f, profile.weightKg, 0.01f)
        assertEquals(0.80f, profile.customStrideLengthMeters, 0.01f)

        val updatedToday = repository.getDailyRecordDirect(today)
        assertNotNull(updatedToday)
        // 10000 * 0.80 = 8000m; 8.0 km * 80 * 0.60 = 384 kcal
        assertEquals(8000f, updatedToday?.distanceMeters ?: 0f, 0.1f)
        assertEquals(384f, updatedToday?.caloriesKcal ?: 0f, 0.1f)
    }

    @Test
    fun processHardwareSensorReading_guardsAgainstIntegerOverflow() = runTest {
        repository.processHardwareSensorReading(0L, "2026-11-10", 10)
        val record = repository.processHardwareSensorReading(Int.MAX_VALUE.toLong() + 5000L, "2026-11-10", 10)
        assertEquals(Int.MAX_VALUE, record.steps)

        val nextRecord = repository.processHardwareSensorReading(Int.MAX_VALUE.toLong() + 5100L, "2026-11-10", 10)
        assertEquals(Int.MAX_VALUE, nextRecord.steps)
    }
}
