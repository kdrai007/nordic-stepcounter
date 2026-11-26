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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class AppTheme(val storageKey: String) {
    NORDIC_DARK("nordic_dark"),
    NORDIC_LIGHT("nordic_light"),
    GRUVBOX_DARK("gruvbox_dark"),
    GRUVBOX_LIGHT("gruvbox_light"),
    CATPPUCCIN_DARK("catppuccin_dark"),
    CATPPUCCIN_LIGHT("catppuccin_light"),
    TOKYONIGHT_DARK("tokyonight_dark"),
    TOKYONIGHT_LIGHT("tokyonight_light");

    companion object {
        fun fromStorageValue(raw: String?): AppTheme {
            if (raw.isNullOrBlank()) return NORDIC_DARK
            return entries.firstOrNull { it.storageKey == raw }
                ?: entries.firstOrNull { it.name == raw }
                ?: NORDIC_DARK
        }
    }
}

data class UserProfile(
    val heightCm: Float = 175.0f,
    val weightKg: Float = 70.0f,
    val isMale: Boolean = true,
    val customStrideLengthMeters: Float = 0.0f,
    val dailyGoal: Int = 6000,
    val appTheme: AppTheme = AppTheme.NORDIC_DARK
) {
    val effectiveStrideLengthMeters: Float
        get() = if (customStrideLengthMeters > 0f) {
            customStrideLengthMeters
        } else {
            StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale)
        }
}

data class LegacySensorState(
    val lastSensorStepValue: Long,
    val lastRecordedDate: String,
    val isPaused: Boolean
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nordic_user_preferences")

class ProfilePreferences(
    private val dataStore: DataStore<Preferences>
) {
    constructor(
        context: Context,
        dataStore: DataStore<Preferences> = context.applicationContext.dataStore
    ) : this(dataStore)

    private object PreferencesKeys {
        val KEY_HEIGHT_CM = floatPreferencesKey("key_height_cm")
        val KEY_WEIGHT_KG = floatPreferencesKey("key_weight_kg")
        val KEY_IS_MALE = booleanPreferencesKey("key_is_male")
        val KEY_CUSTOM_STRIDE_METERS = floatPreferencesKey("key_custom_stride_meters")
        val KEY_DAILY_GOAL = intPreferencesKey("key_daily_goal")
        val KEY_APP_THEME = stringPreferencesKey("key_app_theme")
        val KEY_LAST_BACKUP_TIMESTAMP_MS = longPreferencesKey("key_last_backup_timestamp_ms")

        // Legacy v1 keys migrated once into Room SensorStateEntity
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
                appTheme = AppTheme.fromStorageValue(preferences[PreferencesKeys.KEY_APP_THEME])
            )
        }

    val dailyGoalFlow: Flow<Int> = userProfileFlow.map { it.dailyGoal }.distinctUntilChanged()
    val appThemeFlow: Flow<AppTheme> = userProfileFlow.map { it.appTheme }.distinctUntilChanged()
    val lastBackupTimestampFlow: Flow<Long> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.KEY_LAST_BACKUP_TIMESTAMP_MS] ?: 0L
        }
        .distinctUntilChanged()

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

    suspend fun updateAppTheme(theme: AppTheme) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_APP_THEME] = theme.storageKey
        }
    }

    suspend fun updateLastBackupTimestamp(timestampMs: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_LAST_BACKUP_TIMESTAMP_MS] = timestampMs.coerceAtLeast(0L)
        }
    }

    suspend fun restoreProfile(profile: UserProfile) {
        val safeHeight = if (profile.heightCm.isFinite()) profile.heightCm.coerceIn(100f, 250f) else 175.0f
        val safeWeight = if (profile.weightKg.isFinite()) profile.weightKg.coerceIn(30f, 250f) else 70.0f
        val safeCustomStride = if (profile.customStrideLengthMeters.isFinite() && profile.customStrideLengthMeters > 0f) {
            profile.customStrideLengthMeters.coerceIn(0.30f, 1.50f)
        } else {
            0.0f
        }
        val safeGoal = profile.dailyGoal.coerceIn(1_000, 100_000)

        dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEY_HEIGHT_CM] = safeHeight
            preferences[PreferencesKeys.KEY_WEIGHT_KG] = safeWeight
            preferences[PreferencesKeys.KEY_IS_MALE] = profile.isMale
            preferences[PreferencesKeys.KEY_CUSTOM_STRIDE_METERS] = safeCustomStride
            preferences[PreferencesKeys.KEY_DAILY_GOAL] = safeGoal
            preferences[PreferencesKeys.KEY_APP_THEME] = profile.appTheme.storageKey
        }
    }

    /**
     * Reads and clears legacy v1 sensor-tracking keys from DataStore if present.
     * Used for one-time migration into Room's [com.nordic.stepcounter.data.local.SensorStateEntity].
     */
    suspend fun consumeLegacySensorState(): LegacySensorState? {
        val current = dataStore.data
            .catch { exception ->
                if (exception is IOException) emit(emptyPreferences()) else throw exception
            }
            .first()
        val hasAnyLegacyKey = current.contains(PreferencesKeys.KEY_LAST_SENSOR_STEP_VALUE) ||
            current.contains(PreferencesKeys.KEY_LAST_RECORDED_DATE) ||
            current.contains(PreferencesKeys.KEY_IS_PAUSED)
        if (!hasAnyLegacyKey) return null

        val legacy = LegacySensorState(
            lastSensorStepValue = current[PreferencesKeys.KEY_LAST_SENSOR_STEP_VALUE] ?: -1L,
            lastRecordedDate = current[PreferencesKeys.KEY_LAST_RECORDED_DATE] ?: "",
            isPaused = current[PreferencesKeys.KEY_IS_PAUSED] ?: false
        )
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.KEY_LAST_SENSOR_STEP_VALUE)
            preferences.remove(PreferencesKeys.KEY_LAST_RECORDED_DATE)
            preferences.remove(PreferencesKeys.KEY_IS_PAUSED)
        }
        return legacy
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ProfilePreferences? = null

        fun getInstance(context: Context): ProfilePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfilePreferences(context.applicationContext.dataStore).also { INSTANCE = it }
            }
        }
    }
}
