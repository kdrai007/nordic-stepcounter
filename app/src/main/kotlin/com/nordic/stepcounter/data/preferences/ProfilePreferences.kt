package com.nordic.stepcounter.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nordic.stepcounter.domain.StepCalculationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class UserProfile(
    val heightCm: Float = 175.0f,
    val weightKg: Float = 70.0f,
    val isMale: Boolean = true,
    val customStrideLengthMeters: Float = 0.0f,
    val dailyGoal: Int = 6000,
    val isPaused: Boolean = false,
    val lastSensorStepValue: Long = -1L,
    val lastRecordedDate: String = ""
) {
    val effectiveStrideLengthMeters: Float
        get() = if (customStrideLengthMeters > 0f) {
            customStrideLengthMeters
        } else {
            StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale)
        }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nordic_user_preferences")

class ProfilePreferences(private val context: Context, private val dataStore: DataStore<Preferences> = context.dataStore) {

    private object PreferencesKeys {
        val KEY_HEIGHT_CM = floatPreferencesKey("key_height_cm")
        val KEY_WEIGHT_KG = floatPreferencesKey("key_weight_kg")
        val KEY_IS_MALE = booleanPreferencesKey("key_is_male")
        val KEY_CUSTOM_STRIDE_METERS = floatPreferencesKey("key_custom_stride_meters")
        val KEY_DAILY_GOAL = intPreferencesKey("key_daily_goal")
        val KEY_IS_PAUSED = booleanPreferencesKey("key_is_paused")
        val KEY_LAST_SENSOR_STEP_VALUE = longPreferencesKey("key_last_sensor_step_value")
        val KEY_LAST_RECORDED_DATE = stringPreferencesKey("key_last_recorded_date")
    }

    val userProfileFlow: Flow<UserProfile> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            UserProfile(
                heightCm = preferences[PreferencesKeys.KEY_HEIGHT_CM] ?: 175.0f,
                weightKg = preferences[PreferencesKeys.KEY_WEIGHT_KG] ?: 70.0f,
                isMale = preferences[PreferencesKeys.KEY_IS_MALE] ?: true,
                customStrideLengthMeters = preferences[PreferencesKeys.KEY_CUSTOM_STRIDE_METERS] ?: 0.0f,
                dailyGoal = preferences[PreferencesKeys.KEY_DAILY_GOAL] ?: 6000,
                isPaused = preferences[PreferencesKeys.KEY_IS_PAUSED] ?: false,
                lastSensorStepValue = preferences[PreferencesKeys.KEY_LAST_SENSOR_STEP_VALUE] ?: -1L,
                lastRecordedDate = preferences[PreferencesKeys.KEY_LAST_RECORDED_DATE] ?: ""
            )
        }

    val isPausedFlow: Flow<Boolean> = userProfileFlow.map { it.isPaused }
    val dailyGoalFlow: Flow<Int> = userProfileFlow.map { it.dailyGoal }

    suspend fun setPaused(isPaused: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_IS_PAUSED] = isPaused
        }
    }

    suspend fun updateDailyGoal(goal: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_DAILY_GOAL] = goal
        }
    }

    suspend fun updatePhysicalProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideLengthMeters: Float = 0.0f
    ) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_HEIGHT_CM] = heightCm
            preferences[PreferencesKeys.KEY_WEIGHT_KG] = weightKg
            preferences[PreferencesKeys.KEY_IS_MALE] = isMale
            preferences[PreferencesKeys.KEY_CUSTOM_STRIDE_METERS] = customStrideLengthMeters
        }
    }

    suspend fun updateLastSensorReading(steps: Long, date: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_LAST_SENSOR_STEP_VALUE] = steps
            preferences[PreferencesKeys.KEY_LAST_RECORDED_DATE] = date
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
