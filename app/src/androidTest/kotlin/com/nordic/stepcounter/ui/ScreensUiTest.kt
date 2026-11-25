package com.nordic.stepcounter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.ui.screens.HistoryScreen
import com.nordic.stepcounter.ui.screens.ProfileScreen
import com.nordic.stepcounter.ui.screens.TodayScreen
import com.nordic.stepcounter.ui.theme.NordicStepTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScreensUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun todayScreen_displaysMetricsSemanticsAndDisablesPauseWithoutPermission() {
        var permissionRequested = false
        var downtimeDismissed = false
        val state = StepUiState(
            isLoaded = true,
            todaySteps = 5432,
            dailyGoal = 10000,
            distanceKm = 4.05f,
            caloriesKcal = 240f,
            activeMinutes = 54,
            downtimeGapFromDate = "2026-11-08",
            downtimeGapToDate = "2026-11-10"
        )

        composeRule.setContent {
            NordicStepTheme(appTheme = AppTheme.NORDIC_DARK) {
                TodayScreen(
                    state = state,
                    onTogglePause = {},
                    hasPermission = false,
                    hasNotificationPermission = true,
                    hasStepSensor = true,
                    onRequestPermission = { permissionRequested = true },
                    onDismissDowntimeNotice = { downtimeDismissed = true }
                )
            }
        }

        composeRule.onNodeWithContentDescription("5,432 of 10,000 steps, 54 percent of goal, tracking active")
            .assertIsDisplayed()
        composeRule.onNodeWithText("PAUSE TRACKING").assertIsNotEnabled()

        composeRule.onNodeWithText("GRANT").performClick()
        assertTrue(permissionRequested)

        composeRule.onNodeWithText("OK").performClick()
        assertTrue(downtimeDismissed)
    }

    @Test
    fun historyScreen_switchesTabsAndShowsAllTimePersonalBest() {
        val state = StepUiState(
            isLoaded = true,
            todaySteps = 2400,
            dailyGoal = 8000,
            personalBestSteps = 18500,
            earliestRecordDate = "2026-11-01",
            currentDate = "2026-11-10",
            hourlyToday = listOf(HourlyStepEntity("2026-11-10", 9, 2400)),
            recentRecords = listOf(
                DailyStepEntity("2026-11-09", 8000, 5800f, 300f, 4800L, 8000),
                DailyStepEntity("2026-11-10", 2400, 1700f, 100f, 1440L, 8000)
            )
        )

        composeRule.setContent {
            NordicStepTheme(appTheme = AppTheme.NORDIC_LIGHT) {
                HistoryScreen(state = state)
            }
        }

        composeRule.onNodeWithText("HOURLY AVG").assertIsDisplayed()
        composeRule.onNodeWithText("18,500").assertIsDisplayed()
        composeRule.onNodeWithText("PERSONAL BEST").assertIsDisplayed()

        composeRule.onNodeWithText("7 DAYS").performClick()
        composeRule.onNodeWithText("DAILY AVG").assertIsDisplayed()
    }

    @Test
    fun profileScreen_allowsCustomGoalAndThemeSelection() {
        var selectedGoal = 6000
        var selectedTheme = AppTheme.NORDIC_DARK
        val state = StepUiState(
            isLoaded = true,
            dailyGoal = 6000,
            appTheme = AppTheme.NORDIC_DARK
        )

        composeRule.setContent {
            NordicStepTheme(appTheme = state.appTheme) {
                ProfileScreen(
                    state = state,
                    onSelectGoal = { selectedGoal = it },
                    onSelectTheme = { selectedTheme = it }
                )
            }
        }

        composeRule.onNodeWithText("CUSTOM").performClick()
        composeRule.onNodeWithText("6000").performTextReplacement("7500")
        composeRule.onNodeWithText("SAVE").performClick()
        assertEquals(7500, selectedGoal)

        composeRule.onNodeWithText("Tokyo Night Light").performClick()
        assertEquals(AppTheme.TOKYONIGHT_LIGHT, selectedTheme)
    }
}
