package com.nordic.stepcounter.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_state")
data class SensorStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val lastSensorStepValue: Long = -1L,
    val lastBootCount: Int = -1,
    val lastRecordedDate: String = "",
    val lastEventTimestampMs: Long = 0L,
    val isPaused: Boolean = false,
    val pendingResumeBaseline: Boolean = false,
    val installationId: String = "",
    val downtimeGapFromDate: String = "",
    val downtimeGapToDate: String = ""
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
