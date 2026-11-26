package com.nordic.stepcounter.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.local.SensorStateEntity
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StepRepositoryBackupTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var sourceDb: StepDatabase
    private lateinit var targetDb: StepDatabase
    private lateinit var sourcePrefs: ProfilePreferences
    private lateinit var targetPrefs: ProfilePreferences
    private lateinit var sourceRepo: StepRepository
    private lateinit var targetRepo: StepRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val scope = TestScope(UnconfinedTestDispatcher())
        sourcePrefs = ProfilePreferences(
            PreferenceDataStoreFactory.create(scope = scope) {
                tempFolder.newFile("source_${UUID.randomUUID()}.preferences_pb")
            }
        )
        targetPrefs = ProfilePreferences(
            PreferenceDataStoreFactory.create(scope = scope) {
                tempFolder.newFile("target_${UUID.randomUUID()}.preferences_pb")
            }
        )

        sourceRepo = StepRepository(
            stepDao = sourceDb.stepDao(),
            preferences = sourcePrefs,
            bootCountProvider = { 5 },
            installationIdProvider = { "source-device-id" },
            currentTimeMillisProvider = { 1_760_000_000_000L },
            zoneIdProvider = { ZoneOffset.UTC }
        )
        targetRepo = StepRepository(
            stepDao = targetDb.stepDao(),
            preferences = targetPrefs,
            bootCountProvider = { 12 },
            installationIdProvider = { "target-device-id" },
            currentTimeMillisProvider = { 1_760_100_000_000L },
            zoneIdProvider = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test
    fun captureAndRestoreBackup_mergesStepsAndNeverTouchesSensorState() = runTest {
        sourcePrefs.updatePhysicalProfile(182f, 78f, isMale = true, customStrideLengthMeters = 0.77f)
        sourcePrefs.updateDailyGoal(9500)
        sourcePrefs.updateAppTheme(AppTheme.GRUVBOX_DARK)
        sourceDb.stepDao().upsertDaily(
            DailyStepEntity("2026-11-24", 8200, 6314f, 295.5f, 4920L, 9500)
        )
        sourceDb.stepDao().upsertHourly(HourlyStepEntity("2026-11-24", 9, 4200))
        sourceDb.stepDao().upsertSensorState(
            SensorStateEntity(
                lastSensorStepValue = 99_999L,
                lastBootCount = 5,
                lastRecordedDate = "2026-11-24",
                installationId = "source-device-id"
            )
        )

        val snapshot = sourceRepo.captureBackupSnapshot("1.0.0")
        sourceRepo.markManualBackupCompleted(1_760_000_000_000L)
        assertEquals(1_760_000_000_000L, sourceRepo.lastBackupTimestampFlow.first())

        val targetSensorBefore = SensorStateEntity(
            lastSensorStepValue = 1_500L,
            lastBootCount = 12,
            lastRecordedDate = "2026-11-26",
            installationId = "target-device-id"
        )
        targetDb.stepDao().upsertSensorState(targetSensorBefore)
        targetDb.stepDao().upsertDaily(
            DailyStepEntity("2026-11-24", 1_000, 720f, 30f, 600L, 6000)
        )
        targetDb.stepDao().upsertHourly(HourlyStepEntity("2026-11-24", 18, 1_000))

        val stats = targetRepo.restoreBackupSnapshot(snapshot, restoreProfile = true)
        assertEquals(1, stats.mergedDaysCount)
        assertEquals(1, stats.updatedDaysCount)

        val restoredDay = targetRepo.getDailyRecordDirect("2026-11-24")
        assertNotNull(restoredDay)
        assertEquals(8200, restoredDay?.steps)

        val restoredHours = targetRepo.getHourlyRecordsFlow("2026-11-24").first()
        assertEquals(2, restoredHours.size)
        assertEquals(4200, restoredHours.first { it.hour == 9 }.steps)
        assertEquals(1000, restoredHours.first { it.hour == 18 }.steps)

        val restoredProfile = targetRepo.userProfileFlow.first()
        assertEquals(9500, restoredProfile.dailyGoal)
        assertEquals(AppTheme.GRUVBOX_DARK, restoredProfile.appTheme)
        assertEquals(0.77f, restoredProfile.customStrideLengthMeters, 0.001f)

        val targetSensorAfter = targetRepo.getSensorStateDirect()
        assertEquals(targetSensorBefore, targetSensorAfter)
    }

    @Test
    fun restoreBackup_withoutRestoreProfileFlag_preservesLocalProfile() = runTest {
        sourcePrefs.updateDailyGoal(12_000)
        sourcePrefs.updateAppTheme(AppTheme.TOKYONIGHT_LIGHT)
        val snapshot = sourceRepo.captureBackupSnapshot("1.0.0")

        targetPrefs.updateDailyGoal(7_000)
        targetPrefs.updateAppTheme(AppTheme.NORDIC_DARK)

        targetRepo.restoreBackupSnapshot(snapshot, restoreProfile = false)

        val profileAfter = targetRepo.userProfileFlow.first()
        assertEquals(7_000, profileAfter.dailyGoal)
        assertEquals(AppTheme.NORDIC_DARK, profileAfter.appTheme)
    }
}
