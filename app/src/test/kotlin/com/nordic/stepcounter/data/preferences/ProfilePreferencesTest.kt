package com.nordic.stepcounter.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfilePreferencesTest {

    private lateinit var context: Context
    private lateinit var profilePreferences: ProfilePreferences

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        profilePreferences = ProfilePreferences(context)
        profilePreferences.clear()
    }

    @After
    fun tearDown() = runTest {
        profilePreferences.clear()
    }

    @Test
    fun defaultProfileValues_areCorrect() = runTest {
        val profile = profilePreferences.userProfileFlow.first()

        assertEquals(175.0f, profile.heightCm, 0.001f)
        assertEquals(70.0f, profile.weightKg, 0.001f)
        assertTrue(profile.isMale)
        assertEquals(0.0f, profile.customStrideLengthMeters, 0.001f)
        assertEquals(6000, profile.dailyGoal)
        assertFalse(profile.isPaused)
        assertEquals(-1L, profile.lastSensorStepValue)
        assertEquals("", profile.lastRecordedDate)
        assertEquals(0.72625f, profile.effectiveStrideLengthMeters, 0.001f)
        assertEquals(profile.effectiveStrideLengthMeters, profile.effectiveStrideMeters, 0.001f)
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
        assertEquals(0.68145f, profile.effectiveStrideMeters, 0.001f)
    }

    @Test
    fun updateProfile_alias_updatesValuesCorrectly() = runTest {
        profilePreferences.updateProfile(
            heightCm = 170.0f,
            weightKg = 65.0f,
            isMale = false,
            customStrideMeters = 0.70f
        )

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(170.0f, profile.heightCm, 0.001f)
        assertEquals(65.0f, profile.weightKg, 0.001f)
        assertFalse(profile.isMale)
        assertEquals(0.70f, profile.customStrideLengthMeters, 0.001f)
        assertEquals(0.70f, profile.effectiveStrideMeters, 0.001f)
        assertEquals(0.70f, profile.effectiveStrideLengthMeters, 0.001f)
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
        assertEquals(0.85f, profile.effectiveStrideMeters, 0.001f)
    }

    @Test
    fun updateDailyGoal_updatesGoalAndDailyGoalFlow() = runTest {
        profilePreferences.updateDailyGoal(10000)

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(10000, profile.dailyGoal)

        val goalFromFlow = profilePreferences.dailyGoalFlow.first()
        assertEquals(10000, goalFromFlow)
    }

    @Test
    fun setPaused_togglesPauseStateAndFlow() = runTest {
        assertFalse(profilePreferences.isPausedFlow.first())

        profilePreferences.setPaused(true)
        assertTrue(profilePreferences.isPausedFlow.first())
        assertTrue(profilePreferences.userProfileFlow.first().isPaused)

        profilePreferences.setPaused(false)
        assertFalse(profilePreferences.isPausedFlow.first())
        assertFalse(profilePreferences.userProfileFlow.first().isPaused)
    }

    @Test
    fun updateLastSensorReading_updatesStepsAndDate() = runTest {
        profilePreferences.updateLastSensorReading(steps = 14250L, date = "2026-11-10")

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(14250L, profile.lastSensorStepValue)
        assertEquals("2026-11-10", profile.lastRecordedDate)
    }

    @Test
    fun updateLastSensorStepValue_updatesSensorValue() = runTest {
        profilePreferences.updateLastSensorStepValue(8888L)

        val profile = profilePreferences.userProfileFlow.first()
        assertEquals(8888L, profile.lastSensorStepValue)
    }

    @Test
    fun updateAppTheme_updatesThemeAndAppThemeFlow() = runTest {
        assertEquals(AppTheme.NORDIC_DARK, profilePreferences.appThemeFlow.first())

        profilePreferences.updateAppTheme(AppTheme.CATPPUCCIN_DARK)
        assertEquals(AppTheme.CATPPUCCIN_DARK, profilePreferences.appThemeFlow.first())
        assertEquals(AppTheme.CATPPUCCIN_DARK, profilePreferences.userProfileFlow.first().appTheme)

        profilePreferences.updateAppTheme(AppTheme.TOKYONIGHT_LIGHT)
        assertEquals(AppTheme.TOKYONIGHT_LIGHT, profilePreferences.appThemeFlow.first())
        assertEquals(AppTheme.TOKYONIGHT_LIGHT, profilePreferences.userProfileFlow.first().appTheme)
    }

    @Test
    fun allEightThemes_areSupported() = runTest {
        val expectedThemes = listOf(
            AppTheme.NORDIC_DARK,
            AppTheme.NORDIC_LIGHT,
            AppTheme.GRUVBOX_DARK,
            AppTheme.GRUVBOX_LIGHT,
            AppTheme.CATPPUCCIN_DARK,
            AppTheme.CATPPUCCIN_LIGHT,
            AppTheme.TOKYONIGHT_DARK,
            AppTheme.TOKYONIGHT_LIGHT
        )

        assertEquals(8, AppTheme.entries.size)
        for (theme in expectedThemes) {
            profilePreferences.updateAppTheme(theme)
            assertEquals(theme, profilePreferences.appThemeFlow.first())
            assertEquals(theme, profilePreferences.userProfileFlow.first().appTheme)
        }
    }

    @Test
    fun getInstance_returnsSameInstance() {
        val instance1 = ProfilePreferences.getInstance(context)
        val instance2 = ProfilePreferences.getInstance(context)
        assertSame(instance1, instance2)
    }
}
