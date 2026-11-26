package com.nordic.stepcounter.domain.backup

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.domain.StepCalculationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepBackupMergerTest {

    private val defaultProfile = UserProfile(
        heightCm = 175f,
        weightKg = 70f,
        isMale = true,
        customStrideLengthMeters = 0.75f,
        dailyGoal = 8000,
        appTheme = AppTheme.NORDIC_DARK
    )

    @Test
    fun merge_hourlyFirstPreventsDailyHourlyDriftAndRecalculatesMetrics() {
        val localDaily = listOf(
            DailyStepEntity("2026-11-25", 1_000, 750f, 31.5f, 600L, 8000)
        )
        val localHourly = listOf(
            HourlyStepEntity("2026-11-25", 9, 1_000)
        )
        val snapshot = BackupSnapshot(
            exportedAtEpochMs = 1_760_000_000_000L,
            appVersionName = "1.0.0",
            profile = BackupProfile.fromUserProfile(defaultProfile),
            dailySteps = listOf(
                BackupDailyStep("2026-11-25", 2_000, 1500f, 63f, 1200L, 8000)
            ),
            hourlySteps = listOf(
                BackupHourlyStep("2026-11-25", 17, 2_000)
            )
        )

        val merged = StepBackupMerger.merge(localDaily, localHourly, snapshot, defaultProfile)

        assertEquals(1, merged.mergedDaysCount)
        assertEquals(1, merged.dailyUpserts.size)
        val day = merged.dailyUpserts.single()
        assertEquals(3_000, day.steps)
        assertEquals(
            StepCalculationEngine.calculateDistanceKm(3_000, 0.75f) * 1000f,
            day.distanceMeters,
            0.01f
        )
        assertEquals(
            StepCalculationEngine.calculateCaloriesKcal(3_000, 70f, 0.75f),
            day.caloriesKcal,
            0.01f
        )
        assertEquals(StepCalculationEngine.calculateActiveSeconds(3_000), day.activeDurationSeconds)

        assertEquals(1, merged.hourlyUpserts.size)
        assertEquals(HourlyStepEntity("2026-11-25", 17, 2_000), merged.hourlyUpserts.single())
    }

    @Test
    fun merge_keepsHigherLocalDayWithoutOverwriting() {
        val localDaily = listOf(
            DailyStepEntity("2026-11-20", 12_000, 9000f, 378f, 7200L, 10_000)
        )
        val localHourly = listOf(
            HourlyStepEntity("2026-11-20", 8, 6_000),
            HourlyStepEntity("2026-11-20", 19, 6_000)
        )
        val snapshot = BackupSnapshot(
            exportedAtEpochMs = 1_760_000_000_000L,
            appVersionName = "1.0.0",
            profile = BackupProfile.fromUserProfile(defaultProfile),
            dailySteps = listOf(
                BackupDailyStep("2026-11-20", 5_000, 3750f, 157.5f, 3000L, 6_000),
                BackupDailyStep("2026-11-21", 7_500, 5625f, 236.25f, 4500L, 8_000)
            ),
            hourlySteps = listOf(
                BackupHourlyStep("2026-11-20", 8, 4_000),
                BackupHourlyStep("2026-11-21", 12, 7_500)
            )
        )

        val merged = StepBackupMerger.merge(localDaily, localHourly, snapshot, defaultProfile)

        assertEquals(2, merged.mergedDaysCount)
        assertEquals(1, merged.dailyUpserts.size)
        assertEquals("2026-11-21", merged.dailyUpserts.single().date)
        assertEquals(7_500, merged.dailyUpserts.single().steps)
        assertEquals(1, merged.hourlyUpserts.size)
        assertEquals("2026-11-21", merged.hourlyUpserts.single().date)
    }

    @Test
    fun merge_filtersInvalidDatesAndOutOfRangeHours() {
        val snapshot = BackupSnapshot(
            exportedAtEpochMs = 1_760_000_000_000L,
            appVersionName = "1.0.0",
            profile = BackupProfile.fromUserProfile(defaultProfile),
            dailySteps = listOf(
                BackupDailyStep("not-a-date", 500, 0f, 0f, 0L, 6000)
            ),
            hourlySteps = listOf(
                BackupHourlyStep("2026-11-22", -1, 100),
                BackupHourlyStep("2026-11-22", 24, 200),
                BackupHourlyStep("2026-11-22", 10, 400)
            )
        )

        val merged = StepBackupMerger.merge(emptyList(), emptyList(), snapshot, defaultProfile)
        assertEquals(1, merged.mergedDaysCount)
        assertEquals("2026-11-22", merged.dailyUpserts.single().date)
        assertEquals(400, merged.dailyUpserts.single().steps)
        assertTrue(merged.hourlyUpserts.all { it.hour in 0..23 })
    }
}
