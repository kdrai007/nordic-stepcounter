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
import java.time.LocalDate
import java.time.LocalTime

class StepRepository(
    private val stepDao: StepDao,
    private val preferences: ProfilePreferences
) {
    val userProfileFlow: Flow<UserProfile> = preferences.userProfileFlow
    val isPausedFlow: Flow<Boolean> = preferences.isPausedFlow
    val dailyGoalFlow: Flow<Int> = preferences.dailyGoalFlow
    val appThemeFlow: Flow<AppTheme> = preferences.appThemeFlow

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
    ): DailyStepEntity {
        val existing = stepDao.getDailyRecordDirect(date)
        if (existing != null) return existing

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
        return initial
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
    ): DailyStepEntity {
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
        val newTotalSteps = baseSteps + deltaResult.stepsToAdd

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
        stepDao.upsertDaily(updatedDaily)

        if (deltaResult.stepsToAdd > 0) {
            val existingHourly = stepDao.getHourlyRecordDirect(date, hour)
            val newHourlySteps = (existingHourly?.steps ?: 0) + deltaResult.stepsToAdd
            stepDao.upsertHourly(
                HourlyStepEntity(
                    date = date,
                    hour = hour,
                    steps = newHourlySteps
                )
            )
        }

        return updatedDaily
    }

    suspend fun setPaused(isPaused: Boolean) {
        preferences.setPaused(isPaused)
    }

    suspend fun updateDailyGoal(goal: Int) {
        preferences.updateDailyGoal(goal)
        val today = LocalDate.now().toString()
        stepDao.updateDailyGoal(today, goal)
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
    }
}
