package com.nordic.stepcounter.ui

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import java.time.LocalDate

data class StepUiState(
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
    val effectiveStrideMeters: Float = 0.72f,
    val appTheme: AppTheme = AppTheme.NORDIC_DARK,
    val recentRecords: List<DailyStepEntity> = emptyList(),
    val weeklyRecords: List<DailyStepEntity> = emptyList(),
    val monthlyRecords: List<DailyStepEntity> = emptyList(),
    val hourlyToday: List<HourlyStepEntity> = emptyList(),
    val userProfile: UserProfile = UserProfile(),
    val isTrackingActive: Boolean = true,
    val currentDate: String = LocalDate.now().toString()
) {
    val currentTheme: AppTheme get() = appTheme
}
