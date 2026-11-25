package com.nordic.stepcounter.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfilePreferencesTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var testDataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var profilePreferences: ProfilePreferences

    @Before
    fun setUp() {
        val dataStoreScope = TestScope(UnconfinedTestDispatcher())
        val prefsFile = tempFolder.newFile("prefs_${UUID.randomUUID()}.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { prefsFile }
        )
        profilePreferences = ProfilePreferences(testDataStore)
    }

    @Test
    fun defaultProfileValues_areCorrect() = runTest {
        val profile = profilePreferences.userProfileFlow.first()

        assertEquals(175.0f, profile.heightCm, 0.001f)
        assertEquals(70.0f, profile.weightKg, 0.001f)
        assertTrue(profile.isMale)
        assertEquals(0.0f, profile.customStrideLengthMeters, 0.001f)
        assertEquals(6000, profile.dailyGoal)
        assertEquals(0.72625f, profile.effectiveStrideLengthMeters, 0.001f)
        assertEquals(AppTheme.NORDIC_DARK, profile.appTheme)
        assertEquals(AppTheme.NORDIC_DARK, profilePreferences.appThemeFlow.first())
    }

    @Test
    fun updatePhysicalProfile_updatesValuesCorrectly() = runTest {
        profilePreferences.updatePhysicalProfile(
            heightCm = 165.0f,
            weightKg = 55.0f,
            isMale = false,
            customStrideLengthMeters = 0.0f
        )

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(165.0f, profile.heightCm, 0.001f)
        assertEquals(55.0f, profile.weightKg, 0.001f)
        assertFalse(profile.isMale)
        assertEquals(0.0f, profile.customStrideLengthMeters, 0.001f)
        // 165.0 * 0.413 / 100 = 0.68145
        assertEquals(0.68145f, profile.effectiveStrideLengthMeters, 0.001f)
    }

    @Test
    fun customStrideLength_overridesDefaultCalculation() = runTest {
        profilePreferences.updatePhysicalProfile(
            heightCm = 180.0f,
            weightKg = 75.0f,
            isMale = true,
            customStrideLengthMeters = 0.85f
        )

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(0.85f, profile.customStrideLengthMeters, 0.001f)
        assertEquals(0.85f, profile.effectiveStrideLengthMeters, 0.001f)
    }

    @Test
    fun updateDailyGoal_updatesGoalAndDailyGoalFlow() = runTest {
        profilePreferences.updateDailyGoal(10000)

        assertEquals(10000, profilePreferences.userProfileFlow.first().dailyGoal)
        assertEquals(10000, profilePreferences.dailyGoalFlow.first())
    }

    @Test
    fun updateAppTheme_persistsStorageKeyAndSupportsLegacyEnumNames() = runTest {
        assertEquals(AppTheme.NORDIC_DARK, profilePreferences.appThemeFlow.first())

        profilePreferences.updateAppTheme(AppTheme.CATPPUCCIN_DARK)
        assertEquals(AppTheme.CATPPUCCIN_DARK, profilePreferences.appThemeFlow.first())

        // Legacy enum-name compatibility
        testDataStore.edit { prefs ->
            prefs[stringPreferencesKey("key_app_theme")] = "TOKYONIGHT_LIGHT"
        }
        assertEquals(AppTheme.TOKYONIGHT_LIGHT, profilePreferences.appThemeFlow.first())
    }

    @Test
    fun allEightThemes_areSupported() = runTest {
        assertEquals(8, AppTheme.entries.size)
        for (theme in AppTheme.entries) {
            profilePreferences.updateAppTheme(theme)
            assertEquals(theme, profilePreferences.appThemeFlow.first())
            assertEquals(theme, profilePreferences.userProfileFlow.first().appTheme)
        }
    }

    @Test
    fun consumeLegacySensorState_readsAndClearsOldV1Keys() = runTest {
        assertNull(profilePreferences.consumeLegacySensorState())

        testDataStore.edit { prefs ->
            prefs[longPreferencesKey("key_last_sensor_step_value")] = 14250L
            prefs[stringPreferencesKey("key_last_recorded_date")] = "2026-11-10"
            prefs[booleanPreferencesKey("key_is_paused")] = true
        }

        val migrated = profilePreferences.consumeLegacySensorState()
        assertNotNull(migrated)
        assertEquals(14250L, migrated?.lastSensorStepValue)
        assertEquals("2026-11-10", migrated?.lastRecordedDate)
        assertTrue(migrated?.isPaused == true)

        // Second call returns null because keys were removed once consumed
        assertNull(profilePreferences.consumeLegacySensorState())
    }

    @Test
    fun getInstance_returnsSameInstance() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val instance1 = ProfilePreferences.getInstance(context)
        val instance2 = ProfilePreferences.getInstance(context)
        assertSame(instance1, instance2)
    }
}
