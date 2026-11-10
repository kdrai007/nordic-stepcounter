package com.nordic.stepcounter.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_steps")
data class DailyStepEntity(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val steps: Int,
    val distanceMeters: Float,
    val caloriesKcal: Float,
    val activeDurationSeconds: Long,
    val goalSteps: Int
)
