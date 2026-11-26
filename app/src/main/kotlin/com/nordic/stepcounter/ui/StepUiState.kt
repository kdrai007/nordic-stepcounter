package com.nordic.stepcounter.ui

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import java.time.LocalDate

data class StepUiState(
    val isLoaded: Boolean = false,
    val todaySteps: Int = 0,
    val dailyGoal: Int = 6000,
    val goalProgress: Float = 0f,
    val distanceKm: Float = 0f,
    val caloriesKcal: Float = 0f,
    val activeMinutes: Int = 0,
    val isPaused: Boolean = false,
    val heightCm: Float = 175f,
    val weightKg: Float = 70f,
    val isMale: Boolean = true,
    val customStrideMeters: Float = 0f,
    val effectiveStrideLengthMeters: Float = UserProfile().effectiveStrideLengthMeters,
    val appTheme: AppTheme = AppTheme.NORDIC_DARK,
    val recentRecords: List<DailyStepEntity> = emptyList(),
    val hourlyToday: List<HourlyStepEntity> = emptyList(),
    val personalBestSteps: Int = 0,
    val earliestRecordDate: String = "",
    val downtimeGapFromDate: String = "",
    val downtimeGapToDate: String = "",
    val userProfile: UserProfile = UserProfile(),
    val currentDate: String = LocalDate.now().toString(),
    val lastBackupTimestampMs: Long = 0L
)
