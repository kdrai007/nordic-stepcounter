package com.nordic.stepcounter.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.domain.StepCalculationEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StepViewModel private constructor(
    private val repository: StepRepository,
    externalDateFlow: Flow<String>?,
    currentDateProvider: () -> String,
    zoneIdProvider: () -> ZoneId
) : ViewModel() {

    private val dateRefreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    constructor(
        repository: StepRepository,
        currentDateFlow: Flow<String>
    ) : this(
        repository = repository,
        externalDateFlow = currentDateFlow,
        currentDateProvider = { LocalDate.now().toString() },
        zoneIdProvider = { ZoneId.systemDefault() }
    )

    constructor(
        repository: StepRepository,
        currentDateProvider: () -> String = { LocalDate.now().toString() },
        zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    ) : this(
        repository = repository,
        externalDateFlow = null,
        currentDateProvider = currentDateProvider,
        zoneIdProvider = zoneIdProvider
    )

    private val effectiveDateFlow: Flow<String> = externalDateFlow ?: createMidnightAwareDateFlow(
        refreshSignal = dateRefreshSignal,
        currentDateProvider = currentDateProvider,
        zoneIdProvider = zoneIdProvider
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StepUiState> = effectiveDateFlow
        .onEach { todayDate ->
            repository.initializeTodayRecord(todayDate)
        }
        .flatMapLatest { todayDate ->
            val todayLocal = runCatching { LocalDate.parse(todayDate) }.getOrElse { LocalDate.now() }
            val startDate = todayLocal.minusDays(29).toString()

            val analyticsMetaFlow = combine(
                repository.getBestDayStepsFlow(todayDate),
                repository.getEarliestRecordDateFlow(todayDate)
            ) { bestDay, earliestDate -> bestDay to earliestDate }

            combine(
                repository.userProfileFlow,
                repository.sensorStateFlow,
                repository.getDailyRecordFlow(todayDate),
                repository.getDailyRecordsInRangeFlow(startDate, todayDate),
                combine(
                    repository.getHourlyRecordsFlow(todayDate),
                    analyticsMetaFlow,
                    ::Pair
                )
            ) { profile, sensorState, todayRecord, recentList, hourlyAndMeta ->
                val hourlyList = hourlyAndMeta.first
                val (bestDayFromDb, earliestDateFromDb) = hourlyAndMeta.second

                val steps = todayRecord?.steps ?: 0
                val dailyGoal = todayRecord?.goalSteps?.takeIf { it > 0 } ?: profile.dailyGoal
                val stride = profile.effectiveStrideLengthMeters
                val progress = (steps.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
                val isPaused = sensorState?.isPaused ?: false
                val personalBest = maxOf(bestDayFromDb ?: 0, steps)
                val earliestDate = earliestDateFromDb ?: todayDate

                StepUiState(
                    isLoaded = true,
                    todaySteps = steps,
                    dailyGoal = dailyGoal,
                    goalProgress = progress,
                    distanceKm = StepCalculationEngine.calculateDistanceKm(steps, stride),
                    caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(steps, profile.weightKg, stride),
                    activeMinutes = StepCalculationEngine.calculateActiveMinutes(steps),
                    isPaused = isPaused,
                    heightCm = profile.heightCm,
                    weightKg = profile.weightKg,
                    isMale = profile.isMale,
                    customStrideMeters = profile.customStrideLengthMeters,
                    effectiveStrideLengthMeters = stride,
                    appTheme = profile.appTheme,
                    recentRecords = recentList,
                    hourlyToday = hourlyList,
                    personalBestSteps = personalBest,
                    earliestRecordDate = earliestDate,
                    downtimeGapFromDate = sensorState?.downtimeGapFromDate.orEmpty(),
                    downtimeGapToDate = sensorState?.downtimeGapToDate.orEmpty(),
                    userProfile = profile,
                    currentDate = todayDate
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StepUiState()
        )

    fun refreshCurrentDate() {
        dateRefreshSignal.tryEmit(Unit)
    }

    fun togglePause() {
        viewModelScope.launch {
            val currentPaused = repository.isPausedFlow.first()
            repository.setPaused(!currentPaused)
        }
    }

    fun setPaused(isPaused: Boolean) {
        viewModelScope.launch {
            repository.setPaused(isPaused)
        }
    }

    fun dismissDowntimeNotice() {
        viewModelScope.launch {
            repository.dismissDowntimeNotice()
        }
    }

    fun updateDailyGoal(newGoal: Int) {
        viewModelScope.launch {
            repository.updateDailyGoal(newGoal)
        }
    }

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

    fun updateAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            repository.updateAppTheme(theme)
        }
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
        private const val MIN_MIDNIGHT_DELAY_MS = 1_000L
        private const val MAX_MIDNIGHT_DELAY_MS = 24L * 60L * 60L * 1_000L + 60_000L

        private fun createMidnightAwareDateFlow(
            refreshSignal: Flow<Unit>,
            currentDateProvider: () -> String,
            zoneIdProvider: () -> ZoneId
        ): Flow<String> {
            val midnightTicker = flow {
                while (true) {
                    val dateStr = currentDateProvider()
                    emit(dateStr)
                    val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now(zoneIdProvider()) }
                    val nextMidnight = parsedDate.plusDays(1).atStartOfDay(zoneIdProvider()).toInstant()
                    val delayMillis = Duration.between(Instant.now(), nextMidnight)
                        .toMillis()
                        .coerceIn(MIN_MIDNIGHT_DELAY_MS, MAX_MIDNIGHT_DELAY_MS)
                    delay(delayMillis + 50L)
                }
            }
            val signalDates = refreshSignal.map { currentDateProvider() }
            return merge(midnightTicker, signalDates).distinctUntilChanged()
        }

        fun provideFactory(
            repository: StepRepository
        ): ViewModelProvider.Factory = Factory(repository)

        fun provideFactory(
            context: Context
        ): ViewModelProvider.Factory = provideFactory(StepRepository.getInstance(context))
    }
}
