package com.nordic.stepcounter.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.domain.StepCalculationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class StepViewModel(
    private val repository: StepRepository,
    currentDateProvider: () -> String = { LocalDate.now().toString() }
) : ViewModel() {

    private val todayDate = currentDateProvider()
    private val _isTrackingActive = MutableStateFlow(true)

    val uiState: StateFlow<StepUiState> = combine(
        repository.userProfileFlow,
        repository.getDailyRecordFlow(todayDate),
        repository.getRecentDailyRecordsFlow(30),
        repository.getHourlyRecordsFlow(todayDate),
        _isTrackingActive
    ) { profile, todayRecord, recentList, hourlyList, isTrackingActive ->
        val steps = todayRecord?.steps ?: 0
        val dailyGoal = todayRecord?.goalSteps?.takeIf { it > 0 } ?: profile.dailyGoal
        val stride = profile.effectiveStrideMeters
        val progress = (steps.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

        StepUiState(
            todaySteps = steps,
            dailyGoal = dailyGoal,
            goalProgress = progress,
            distanceKm = StepCalculationEngine.calculateDistanceKm(steps, stride),
            caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(steps, profile.weightKg),
            activeMinutes = StepCalculationEngine.calculateActiveMinutes(steps),
            isPaused = profile.isPaused,
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            isMale = profile.isMale,
            customStrideMeters = profile.customStrideLengthMeters,
            effectiveStrideMeters = stride,
            appTheme = profile.appTheme,
            recentRecords = recentList,
            weeklyRecords = recentList.take(7),
            monthlyRecords = recentList.take(30),
            hourlyToday = hourlyList,
            userProfile = profile,
            isTrackingActive = isTrackingActive
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StepUiState()
    )

    init {
        viewModelScope.launch {
            repository.initializeTodayRecord()
        }
    }

    fun togglePause() {
        viewModelScope.launch {
            val currentPaused = uiState.value.isPaused
            repository.setPaused(!currentPaused)
        }
    }

    fun setPaused(isPaused: Boolean) {
        viewModelScope.launch {
            repository.setPaused(isPaused)
        }
    }

    fun updateDailyGoal(newGoal: Int) {
        viewModelScope.launch {
            repository.updateDailyGoal(newGoal)
        }
    }

    fun updateGoal(newGoal: Int) = updateDailyGoal(newGoal)

    fun updatePhysicalProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideMeters: Float = 0f
    ) {
        viewModelScope.launch {
            repository.updatePhysicalProfile(
                heightCm = heightCm,
                weightKg = weightKg,
                isMale = isMale,
                customStrideLengthMeters = customStrideMeters
            )
        }
    }

    fun updateProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideMeters: Float = 0f
    ) = updatePhysicalProfile(heightCm, weightKg, isMale, customStrideMeters)

    fun updateAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            repository.updateAppTheme(theme)
        }
    }

    fun selectTheme(theme: AppTheme) = updateAppTheme(theme)

    fun setTrackingActive(active: Boolean) {
        _isTrackingActive.value = active
    }

    class Factory(private val repository: StepRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StepViewModel::class.java)) {
                return StepViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        fun provideFactory(
            repository: StepRepository
        ): ViewModelProvider.Factory = Factory(repository)

        fun provideFactory(
            context: Context
        ): ViewModelProvider.Factory = provideFactory(StepRepository.getInstance(context))
    }
}
