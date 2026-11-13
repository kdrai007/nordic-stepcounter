package com.nordic.stepcounter.ui

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.data.repository.StepRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: StepRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.userProfileFlow } returns flowOf(UserProfile())
        every { repository.getDailyRecordFlow(any()) } returns flowOf(null)
        every { repository.getRecentDailyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState combines profile, daily steps, and theme accurately`() = runTest {
        val testProfile = UserProfile(
            heightCm = 180f,
            weightKg = 75f,
            isMale = true,
            customStrideLengthMeters = 0.75f,
            dailyGoal = 8000,
            isPaused = false,
            lastSensorStepValue = 1000L,
            appTheme = AppTheme.CATPPUCCIN_DARK
        )
        val testDaily = DailyStepEntity(
            date = "2026-11-10",
            steps = 4000,
            distanceMeters = 3000f,
            caloriesKcal = 225f,
            activeDurationSeconds = 2400L,
            goalSteps = 8000
        )
        val hourlyList = listOf(
            HourlyStepEntity("2026-11-10", 8, 1000),
            HourlyStepEntity("2026-11-10", 9, 3000)
        )
        val recentList = listOf(testDaily)

        every { repository.userProfileFlow } returns flowOf(testProfile)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(testDaily)
        every { repository.getRecentDailyRecordsFlow(any()) } returns flowOf(recentList)
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(hourlyList)

        val viewModel = StepViewModel(repository, currentDateProvider = { "2026-11-10" })
        val state = viewModel.uiState.first { it.todaySteps == 4000 }

        assertEquals(4000, state.todaySteps)
        assertEquals(8000, state.dailyGoal)
        assertEquals(0.5f, state.goalProgress, 0.001f)
        assertEquals(3.0f, state.distanceKm, 0.01f)
        assertEquals(225f, state.caloriesKcal, 0.1f)
        assertEquals(40, state.activeMinutes)
        assertFalse(state.isPaused)
        assertEquals(AppTheme.CATPPUCCIN_DARK, state.appTheme)
        assertEquals(AppTheme.CATPPUCCIN_DARK, state.currentTheme)
        assertEquals(180f, state.heightCm, 0.01f)
        assertEquals(75f, state.weightKg, 0.01f)
        assertTrue(state.isMale)
        assertEquals(0.75f, state.customStrideMeters, 0.01f)
        assertEquals(0.75f, state.effectiveStrideMeters, 0.01f)
        assertEquals(1, state.recentRecords.size)
        assertEquals(1, state.weeklyRecords.size)
        assertEquals(1, state.monthlyRecords.size)
        assertEquals(2, state.hourlyToday.size)
        assertTrue(state.isTrackingActive)
    }

    @Test
    fun `togglePause inverts isPaused status and notifies repository`() = runTest {
        val testProfile = UserProfile(isPaused = false)
        every { repository.userProfileFlow } returns flowOf(testProfile)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(null)
        every { repository.getRecentDailyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())

        val viewModel = StepViewModel(repository)
        viewModel.togglePause()

        coVerify { repository.setPaused(true) }
    }

    @Test
    fun `setPaused directly updates paused state`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.setPaused(true)
        coVerify { repository.setPaused(true) }

        viewModel.setPaused(false)
        coVerify { repository.setPaused(false) }
    }

    @Test
    fun `updateDailyGoal and updateGoal update repository daily goal`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updateDailyGoal(10000)
        coVerify { repository.updateDailyGoal(10000) }

        viewModel.updateGoal(12000)
        coVerify { repository.updateDailyGoal(12000) }
    }

    @Test
    fun `updatePhysicalProfile and updateProfile delegate to repository`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updatePhysicalProfile(182f, 78f, false, 0.78f)
        coVerify {
            repository.updatePhysicalProfile(182f, 78f, false, 0.78f)
        }

        viewModel.updateProfile(170f, 65f, true, 0.70f)
        coVerify {
            repository.updatePhysicalProfile(170f, 65f, true, 0.70f)
        }
    }

    @Test
    fun `selectTheme and updateAppTheme update theme in repository`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updateAppTheme(AppTheme.GRUVBOX_DARK)
        coVerify { repository.updateAppTheme(AppTheme.GRUVBOX_DARK) }

        viewModel.selectTheme(AppTheme.TOKYONIGHT_LIGHT)
        coVerify { repository.updateAppTheme(AppTheme.TOKYONIGHT_LIGHT) }
    }

    @Test
    fun `goalProgress clamps correctly at 0 and 1`() = runTest {
        val testProfile = UserProfile(dailyGoal = 5000)
        val overDaily = DailyStepEntity(
            date = "2026-11-10",
            steps = 7500,
            distanceMeters = 5000f,
            caloriesKcal = 350f,
            activeDurationSeconds = 3000L,
            goalSteps = 5000
        )
        every { repository.userProfileFlow } returns flowOf(testProfile)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(overDaily)
        every { repository.getRecentDailyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())

        val viewModel = StepViewModel(repository, currentDateProvider = { "2026-11-10" })
        val state = viewModel.uiState.first { it.todaySteps == 7500 }

        assertEquals(1.0f, state.goalProgress, 0.001f)
    }

    @Test
    fun `setTrackingActive toggles tracking flag in uiState`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.setTrackingActive(false)
        val state = viewModel.uiState.first { !it.isTrackingActive }
        assertFalse(state.isTrackingActive)
    }

    @Test
    fun `Factory creates StepViewModel instance`() {
        val factory = StepViewModel.Factory(repository)
        val vm = factory.create(StepViewModel::class.java)
        assertNotNull(vm)

        val companionFactory = StepViewModel.provideFactory(repository)
        val vmCompanion = companionFactory.create(StepViewModel::class.java)
        assertNotNull(vmCompanion)
    }
}
