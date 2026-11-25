package com.nordic.stepcounter.data.repository

import android.content.Context
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.local.StepDao
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.domain.StepDeltaProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime

class StepRepository(
    private val stepDao: StepDao,
    private val preferences: ProfilePreferences
) {
    private val sensorMutex = Mutex()

    val userProfileFlow: Flow<UserProfile> = preferences.userProfileFlow
    val isPausedFlow: Flow<Boolean> = preferences.isPausedFlow
    val dailyGoalFlow: Flow<Int> = preferences.dailyGoalFlow
    val appThemeFlow: Flow<AppTheme> = preferences.appThemeFlow

    val todayStepRecordFlow: Flow<DailyStepEntity?>
        get() = getDailyRecordFlow(LocalDate.now().toString())

    fun getLast7DaysRecordsFlow(): Flow<List<DailyStepEntity>> =
        getRecentDailyRecordsFlow(7)

    fun getLast30DaysRecordsFlow(): Flow<List<DailyStepEntity>> =
        getRecentDailyRecordsFlow(30)

    fun getHourlyRecordsForTodayFlow(): Flow<List<HourlyStepEntity>> =
        getHourlyRecordsFlow(LocalDate.now().toString())

    fun getDailyRecordFlow(date: String): Flow<DailyStepEntity?> =
        stepDao.getDailyRecord(date)

    suspend fun getDailyRecordDirect(date: String): DailyStepEntity? =
        stepDao.getDailyRecordDirect(date)

    fun getRecentDailyRecordsFlow(limit: Int): Flow<List<DailyStepEntity>> =
        stepDao.getRecentDailyRecords(limit)

    fun getHourlyRecordsFlow(date: String): Flow<List<HourlyStepEntity>> =
        stepDao.getHourlyRecordsForDate(date)

    suspend fun getTotalLifetimeSteps(): Long =
        stepDao.getTotalLifetimeSteps() ?: 0L

    /**
     * Initializes a record for [date] if one does not already exist.
     * Ensures midnight rollover or new day start has a valid 0-step baseline.
     */
    suspend fun initializeTodayRecord(
        date: String = LocalDate.now().toString()
    ): DailyStepEntity = sensorMutex.withLock {
        val existing = stepDao.getDailyRecordDirect(date)
        if (existing != null) return@withLock existing

        val profile = preferences.userProfileFlow.first()
        val initial = DailyStepEntity(
            date = date,
            steps = 0,
            distanceMeters = 0f,
            caloriesKcal = 0f,
            activeDurationSeconds = 0L,
            goalSteps = profile.dailyGoal
        )
        stepDao.upsertDaily(initial)
        return@withLock initial
    }

    /**
     * Ingests a raw hardware Sensor.TYPE_STEP_COUNTER reading.
     * Handles delta calculation, midnight rollover, pause state, and updates
     * Room daily & hourly step records alongside DataStore sensor baseline.
     */
    suspend fun processHardwareSensorReading(
        rawSensorSteps: Long,
        date: String = LocalDate.now().toString(),
        hour: Int = LocalTime.now().hour
    ): DailyStepEntity = sensorMutex.withLock {
        val profile = preferences.userProfileFlow.first()

        val deltaResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = rawSensorSteps,
            lastSensorSteps = profile.lastSensorStepValue,
            isPaused = profile.isPaused
        )

        // Advance baseline and record latest date
        preferences.updateLastSensorReading(
            steps = deltaResult.newBaselineSensorSteps,
            date = date
        )

        val existingDaily = stepDao.getDailyRecordDirect(date)
        val baseSteps = existingDaily?.steps ?: 0
        val newTotalSteps = (baseSteps.toLong() + deltaResult.stepsToAdd)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val strideMeters = profile.effectiveStrideLengthMeters
        val distanceMeters = StepCalculationEngine.calculateDistanceKm(newTotalSteps, strideMeters) * 1000f
        val caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(newTotalSteps, profile.weightKg)
        val activeDurationSeconds = StepCalculationEngine.calculateActiveMinutes(newTotalSteps) * 60L

        val updatedDaily = DailyStepEntity(
            date = date,
            steps = newTotalSteps,
            distanceMeters = distanceMeters,
            caloriesKcal = caloriesKcal,
            activeDurationSeconds = activeDurationSeconds,
            goalSteps = profile.dailyGoal
        )

        val hourlyEntity = if (deltaResult.stepsToAdd > 0) {
            val existingHourly = stepDao.getHourlyRecordDirect(date, hour)
            val newHourlySteps = ((existingHourly?.steps ?: 0).toLong() + deltaResult.stepsToAdd)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            HourlyStepEntity(
                date = date,
                hour = hour,
                steps = newHourlySteps
            )
        } else {
            null
        }

        stepDao.upsertDailyAndHourly(updatedDaily, hourlyEntity)

        return@withLock updatedDaily
    }

    suspend fun setPaused(isPaused: Boolean) = sensorMutex.withLock {
        preferences.setPaused(isPaused)
    }

    suspend fun updateDailyGoal(goal: Int) = sensorMutex.withLock {
        val safeGoal = goal.coerceIn(1_000, 100_000)
        preferences.updateDailyGoal(safeGoal)
        val today = LocalDate.now().toString()
        val affected = stepDao.updateDailyGoal(today, safeGoal)
        if (affected == 0) {
            val existing = stepDao.getDailyRecordDirect(today)
            if (existing == null) {
                stepDao.upsertDaily(
                    DailyStepEntity(
                        date = today,
                        steps = 0,
                        distanceMeters = 0f,
                        caloriesKcal = 0f,
                        activeDurationSeconds = 0L,
                        goalSteps = safeGoal
                    )
                )
            }
        }
    }

    suspend fun updatePhysicalProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideLengthMeters: Float = 0.0f
    ) {
        preferences.updatePhysicalProfile(heightCm, weightKg, isMale, customStrideLengthMeters)
    }

    suspend fun updateAppTheme(theme: AppTheme) {
        preferences.updateAppTheme(theme)
    }

    companion object {
        @Volatile
        private var INSTANCE: StepRepository? = null

        fun getInstance(context: Context): StepRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(
                    StepDatabase.getInstance(context).stepDao(),
                    ProfilePreferences.getInstance(context)
                ).also { INSTANCE = it }
            }
        }

        fun getInstance(stepDao: StepDao, preferences: ProfilePreferences): StepRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(stepDao, preferences).also { INSTANCE = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
