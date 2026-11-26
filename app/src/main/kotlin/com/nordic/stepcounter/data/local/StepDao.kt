package com.nordic.stepcounter.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StepDao {
    @Query("SELECT * FROM daily_steps WHERE date = :date LIMIT 1")
    fun getDailyRecord(date: String): Flow<DailyStepEntity?>

    @Query("SELECT * FROM daily_steps WHERE date = :date LIMIT 1")
    suspend fun getDailyRecordDirect(date: String): DailyStepEntity?

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :limit")
    fun getRecentDailyRecords(limit: Int): Flow<List<DailyStepEntity>>

    @Query("SELECT * FROM daily_steps WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    fun getDailyRecordsInRange(startDate: String, endDate: String): Flow<List<DailyStepEntity>>

    @Query("SELECT MAX(steps) FROM daily_steps WHERE date <= :maxDate")
    fun getBestDaySteps(maxDate: String): Flow<Int?>

    @Query("SELECT MIN(date) FROM daily_steps WHERE date <= :maxDate")
    fun getEarliestRecordDate(maxDate: String): Flow<String?>

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour ASC")
    fun getHourlyRecordsForDate(date: String): Flow<List<HourlyStepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date AND hour = :hour LIMIT 1")
    suspend fun getHourlyRecordDirect(date: String, hour: Int): HourlyStepEntity?

    @Query("SELECT * FROM daily_steps ORDER BY date ASC")
    suspend fun getAllDailyRecordsDirect(): List<DailyStepEntity>

    @Query("SELECT * FROM hourly_steps ORDER BY date ASC, hour ASC")
    suspend fun getAllHourlyRecordsDirect(): List<HourlyStepEntity>

    @Query("SELECT * FROM sensor_state WHERE id = ${SensorStateEntity.SINGLETON_ID} LIMIT 1")
    fun getSensorStateFlow(): Flow<SensorStateEntity?>

    @Query("SELECT * FROM sensor_state WHERE id = ${SensorStateEntity.SINGLETON_ID} LIMIT 1")
    suspend fun getSensorStateDirect(): SensorStateEntity?

    @Upsert
    suspend fun upsertSensorState(entity: SensorStateEntity)

    @Upsert
    suspend fun upsertDaily(entity: DailyStepEntity)

    @Upsert
    suspend fun upsertHourly(entity: HourlyStepEntity)

    @Upsert
    suspend fun upsertAllDaily(entities: List<DailyStepEntity>)

    @Upsert
    suspend fun upsertAllHourly(entities: List<HourlyStepEntity>)

    @Transaction
    suspend fun applyMergedBackup(
        daily: List<DailyStepEntity>,
        hourly: List<HourlyStepEntity>
    ) {
        if (daily.isNotEmpty()) upsertAllDaily(daily)
        if (hourly.isNotEmpty()) upsertAllHourly(hourly)
    }

    @Transaction
    suspend fun upsertDailyAndHourly(daily: DailyStepEntity, hourly: HourlyStepEntity?) {
        upsertDaily(daily)
        if (hourly != null) {
            upsertHourly(hourly)
        }
    }

    @Transaction
    suspend fun persistSensorUpdate(
        sensorState: SensorStateEntity,
        daily: DailyStepEntity,
        hourly: HourlyStepEntity?
    ) {
        upsertSensorState(sensorState)
        upsertDaily(daily)
        if (hourly != null) {
            upsertHourly(hourly)
        }
    }

    @Query("UPDATE daily_steps SET goalSteps = :goal WHERE date = :date")
    suspend fun updateDailyGoal(date: String, goal: Int): Int

    @Query("SELECT SUM(steps) FROM daily_steps")
    suspend fun getTotalLifetimeSteps(): Long?
}
