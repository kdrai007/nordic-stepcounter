package com.nordic.stepcounter.ui

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.local.SensorStateEntity
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.ui.screens.calculateWindowDailyAverage
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class StepViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: StepRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.userProfileFlow } returns flowOf(UserProfile())
        every { repository.sensorStateFlow } returns flowOf(SensorStateEntity())
        every { repository.isPausedFlow } returns flowOf(false)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(null)
        every { repository.getDailyRecordsInRangeFlow(any(), any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getBestDayStepsFlow(any()) } returns flowOf(null)
        every { repository.getEarliestRecordDateFlow(any()) } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState combines profile, sensorState, daily steps, personal best, and theme accurately`() = runTest {
        val testProfile = UserProfile(
            heightCm = 180f,
            weightKg = 80f,
            isMale = true,
            customStrideLengthMeters = 0.75f,
            dailyGoal = 8000,
            appTheme = AppTheme.CATPPUCCIN_DARK
        )
        val testSensorState = SensorStateEntity(
            lastSensorStepValue = 1000L,
            isPaused = true,
            downtimeGapFromDate = "2026-11-01",
            downtimeGapToDate = "2026-11-10"
        )
        val testDaily = DailyStepEntity(
            date = "2026-11-10",
            steps = 4000,
            distanceMeters = 3000f,
            caloriesKcal = 144f,
            activeDurationSeconds = 2400L,
            goalSteps = 8000
        )
        val hourlyList = listOf(
            HourlyStepEntity("2026-11-10", 8, 1000),
            HourlyStepEntity("2026-11-10", 9, 3000)
        )
        val recentList = listOf(testDaily)

        every { repository.userProfileFlow } returns flowOf(testProfile)
        every { repository.sensorStateFlow } returns flowOf(testSensorState)
        every { repository.getDailyRecordFlow("2026-11-10") } returns flowOf(testDaily)
        every { repository.getDailyRecordsInRangeFlow("2026-10-12", "2026-11-10") } returns flowOf(recentList)
        every { repository.getHourlyRecordsFlow("2026-11-10") } returns flowOf(hourlyList)
        every { repository.getBestDayStepsFlow("2026-11-10") } returns flowOf(18500)
        every { repository.getEarliestRecordDateFlow("2026-11-10") } returns flowOf("2026-09-01")

        val viewModel = StepViewModel(repository, currentDateProvider = { "2026-11-10" })
        val state = viewModel.uiState.first { it.isLoaded && it.todaySteps == 4000 }

        assertTrue(state.isLoaded)
        assertEquals(4000, state.todaySteps)
        assertEquals(8000, state.dailyGoal)
        assertEquals(0.5f, state.goalProgress, 0.001f)
        // 4000 * 0.75 / 1000 = 3.0 km; 3.0 * 80 * 0.60 = 144 kcal
        assertEquals(3.0f, state.distanceKm, 0.01f)
        assertEquals(144f, state.caloriesKcal, 0.1f)
        assertEquals(40, state.activeMinutes)
        assertTrue(state.isPaused)
        assertEquals(AppTheme.CATPPUCCIN_DARK, state.appTheme)
        assertEquals(180f, state.heightCm, 0.01f)
        assertEquals(80f, state.weightKg, 0.01f)
        assertTrue(state.isMale)
        assertEquals(0.75f, state.customStrideMeters, 0.01f)
        assertEquals(0.75f, state.effectiveStrideLengthMeters, 0.01f)
        assertEquals(1, state.recentRecords.size)
        assertEquals(2, state.hourlyToday.size)
        assertEquals(18500, state.personalBestSteps)
        assertEquals("2026-09-01", state.earliestRecordDate)
        assertEquals("2026-11-01", state.downtimeGapFromDate)
        assertEquals("2026-11-10", state.downtimeGapToDate)
    }

    @Test
    fun `togglePause inverts isPaused status and notifies repository`() = runTest {
        every { repository.isPausedFlow } returns flowOf(false)

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
    fun `dismissDowntimeNotice delegates to repository`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.dismissDowntimeNotice()
        coVerify { repository.dismissDowntimeNotice() }
    }

    @Test
    fun `updateDailyGoal updates repository daily goal`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updateDailyGoal(10000)
        coVerify { repository.updateDailyGoal(10000) }
    }

    @Test
    fun `updatePhysicalProfile delegates to repository`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updatePhysicalProfile(182f, 78f, false, 0.78f)
        coVerify {
            repository.updatePhysicalProfile(182f, 78f, false, 0.78f)
        }
    }

    @Test
    fun `updateAppTheme updates theme in repository`() = runTest {
        val viewModel = StepViewModel(repository)
        viewModel.updateAppTheme(AppTheme.GRUVBOX_DARK)
        coVerify { repository.updateAppTheme(AppTheme.GRUVBOX_DARK) }
    }

    @Test
    fun `goalProgress clamps correctly at 0 and 1`() = runTest {
        val testProfile = UserProfile(dailyGoal = 5000)
        val overDaily = DailyStepEntity("2026-11-10", 7500, 5000f, 350f, 3000L, 5000)
        every { repository.userProfileFlow } returns flowOf(testProfile)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(overDaily)

        val viewModel = StepViewModel(repository, currentDateProvider = { "2026-11-10" })
        val state = viewModel.uiState.first { it.todaySteps == 7500 }

        assertEquals(1.0f, state.goalProgress, 0.001f)
    }

    @Test
    fun `dateRollover updates uiState to observe new date and initializes record`() = runTest {
        val day1Record = DailyStepEntity("2026-11-10", 5000, 3500f, 250f, 3000L, 8000)
        val day2Record = DailyStepEntity("2026-11-11", 200, 150f, 10f, 120L, 8000)

        val dateFlow = MutableStateFlow("2026-11-10")
        every { repository.getDailyRecordFlow("2026-11-10") } returns flowOf(day1Record)
        every { repository.getDailyRecordFlow("2026-11-11") } returns flowOf(day2Record)

        val viewModel = StepViewModel(repository, currentDateFlow = dateFlow)
        val state1 = viewModel.uiState.first { it.todaySteps == 5000 }
        assertEquals(5000, state1.todaySteps)
        assertEquals("2026-11-10", state1.currentDate)

        dateFlow.value = "2026-11-11"
        val state2 = viewModel.uiState.first { it.todaySteps == 200 }
        assertEquals(200, state2.todaySteps)
        assertEquals("2026-11-11", state2.currentDate)

        coVerify { repository.initializeTodayRecord("2026-11-10") }
        coVerify { repository.initializeTodayRecord("2026-11-11") }
    }

    @Test
    fun `refreshCurrentDate triggers re-evaluation when provider date changes`() = runTest {
        var currentDate = "2026-11-10"
        val day1Record = DailyStepEntity("2026-11-10", 3000, 2000f, 100f, 1800L, 6000)
        val day2Record = DailyStepEntity("2026-11-11", 120, 80f, 5f, 72L, 6000)
        every { repository.getDailyRecordFlow("2026-11-10") } returns flowOf(day1Record)
        every { repository.getDailyRecordFlow("2026-11-11") } returns flowOf(day2Record)

        val viewModel = StepViewModel(repository, currentDateProvider = { currentDate })
        val state1 = viewModel.uiState.first { it.currentDate == "2026-11-10" && it.todaySteps == 3000 }
        assertEquals(3000, state1.todaySteps)

        currentDate = "2026-11-11"
        viewModel.refreshCurrentDate()
        val state2 = viewModel.uiState.first { it.currentDate == "2026-11-11" && it.todaySteps == 120 }
        assertEquals(120, state2.todaySteps)
    }

    @Test
    fun `calculateWindowDailyAverage handles skipped days, incomplete today, and day one`() {
        val today = LocalDate.of(2026, 11, 10)
        val records = mapOf(
            "2026-11-07" to DailyStepEntity("2026-11-07", 9000, 0f, 0f, 0L, 6000),
            // 2026-11-08 intentionally missing (skipped day = 0 steps)
            "2026-11-09" to DailyStepEntity("2026-11-09", 6000, 0f, 0f, 0L, 6000),
            // 2026-11-10 is today at 0 steps (incomplete, excluded when prior days exist)
            "2026-11-10" to DailyStepEntity("2026-11-10", 0, 0f, 0f, 0L, 6000)
        )

        // Completed days since earliest (Nov 7..Nov 9) = 3 days -> (9000 + 0 + 6000) / 3 = 5000
        val avg7 = calculateWindowDailyAverage(
            today = today,
            windowDays = 7,
            recordsByDate = records,
            earliestRecordDateStr = "2026-11-07",
            todaySteps = 0
        )
        assertEquals(5000, avg7)

        // Day 1 user (earliest == today): falls back to todaySteps
        val day1Avg = calculateWindowDailyAverage(
            today = today,
            windowDays = 7,
            recordsByDate = mapOf("2026-11-10" to DailyStepEntity("2026-11-10", 4200, 0f, 0f, 0L, 6000)),
            earliestRecordDateStr = "2026-11-10",
            todaySteps = 4200
        )
        assertEquals(4200, day1Avg)
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
