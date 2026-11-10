package com.nordic.stepcounter.data.local

import androidx.room.Dao
import androidx.room.Query
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

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour ASC")
    fun getHourlyRecordsForDate(date: String): Flow<List<HourlyStepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date AND hour = :hour LIMIT 1")
    suspend fun getHourlyRecordDirect(date: String, hour: Int): HourlyStepEntity?

    @Upsert
    suspend fun upsertDaily(entity: DailyStepEntity)

    @Upsert
    suspend fun upsertHourly(entity: HourlyStepEntity)

    @Query("UPDATE daily_steps SET goalSteps = :goal WHERE date = :date")
    suspend fun updateDailyGoal(date: String, goal: Int): Int

    @Query("SELECT SUM(steps) FROM daily_steps")
    suspend fun getTotalLifetimeSteps(): Long?
}
