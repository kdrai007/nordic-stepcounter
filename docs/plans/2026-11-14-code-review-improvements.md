# Code Review Improvements Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve all architectural, concurrency, UI/UX, battery-efficiency, Android 15 edge-to-edge, and build issues identified in the Gemini Argon review for the Nordic Step Counter.

**Architecture:** 
1. Fix the `StepRepository` singleton split-brain in `MainActivity` and isolate shared state with `sensorMutex`.
2. Add dynamic date observation in `StepViewModel` to guarantee seamless midnight rollover.
3. Synchronize `StepCounterService` notification and sensor FIFO flushing with UI pause/resume actions.
4. Enhance `ProfileScreen` with full biometric and stride calibration controls.
5. Fix `HistoryScreen` calendar-day alignment and 1-bar rendering defect.
6. Support Android 15 edge-to-edge system bar contrast in `Theme.kt`, preserve tab state across configuration changes, and prune build dependencies.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Room, DataStore Preferences, Coroutines/Flow, Android Sensors API, Robolectric.

---

### Task 1: Unify `StepRepository` Singleton & Mutex Locking

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/data/repository/StepRepository.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt:43-47`
- Test: `app/src/test/kotlin/com/nordic/stepcounter/data/repository/StepRepositoryTest.kt`

**Step 1: Write the test verifying mutex locking on write operations**
Verify that `initializeTodayRecord` and `updateDailyGoal` operate consistently without racing against sensor readings.

**Step 2: Update `StepRepository.kt`**
Wrap `initializeTodayRecord` and `updateDailyGoal` with `sensorMutex.withLock`:
```kotlin
suspend fun initializeTodayRecord(
    date: String = LocalDate.now().toString()
): DailyStepEntity = sensorMutex.withLock {
    val existing = stepDao.getDailyRecordDirect(date)
    if (existing != null) return@withLock existing

    val profile = preferences.userProfileFlow.first()
    val initial = DailyStepEntity(
        date = date,
        steps = 0,
        distanceMeters = 0f,
        caloriesKcal = 0f,
        activeDurationSeconds = 0L,
        goalSteps = profile.dailyGoal
    )
    stepDao.upsertDaily(initial)
    return@withLock initial
}

suspend fun updateDailyGoal(goal: Int) = sensorMutex.withLock {
    preferences.updateDailyGoal(goal)
    val today = LocalDate.now().toString()
    val affected = stepDao.updateDailyGoal(today, goal)
    if (affected == 0) {
        val existing = stepDao.getDailyRecordDirect(today)
        if (existing == null) {
            stepDao.upsertDaily(
                DailyStepEntity(
                    date = today,
                    steps = 0,
                    distanceMeters = 0f,
                    caloriesKcal = 0f,
                    activeDurationSeconds = 0L,
                    goalSteps = goal
                )
            )
        }
    }
}
```

**Step 3: Update `MainActivity.kt` to use the singleton factory**
Replace direct instantiation of `StepRepository` with `StepViewModel.provideFactory(this)`:
```kotlin
private val viewModel: StepViewModel by viewModels {
    StepViewModel.provideFactory(applicationContext)
}
```

**Step 4: Run tests**
Run: `./gradlew testDebugUnitTest --tests com.nordic.stepcounter.data.repository.StepRepositoryTest`
Expected: PASS

---

### Task 2: Midnight Rollover Bug in `StepViewModel`

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/StepViewModel.kt`
- Test: `app/src/test/kotlin/com/nordic/stepcounter/ui/StepViewModelTest.kt`

**Step 1: Write the failing test in `StepViewModelTest.kt`**
Verify that when the date changes, `StepViewModel` switches to observing the new day's record flow instead of staying pinned to the initial date.

**Step 2: Refactor `StepViewModel.kt` to observe a reactive date flow**
Introduce `currentDateFlow: StateFlow<String>` (with date rollover checking) and use `flatMapLatest`:
```kotlin
class StepViewModel(
    private val repository: StepRepository,
    private val dateFlow: Flow<String> = flow {
        while (true) {
            emit(LocalDate.now().toString())
            delay(10_000) // periodic date check
        }
    }.distinctUntilChanged()
) : ViewModel() {

    private val _isTrackingActive = MutableStateFlow(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StepUiState> = dateFlow.flatMapLatest { todayDate ->
        repository.initializeTodayRecord(todayDate)
        combine(
            repository.userProfileFlow,
            repository.getDailyRecordFlow(todayDate),
            repository.getRecentDailyRecordsFlow(30),
            repository.getHourlyRecordsFlow(todayDate),
            _isTrackingActive
        ) { profile, todayRecord, recentList, hourlyList, isTrackingActive ->
            val steps = todayRecord?.steps ?: 0
            val dailyGoal = todayRecord?.goalSteps?.takeIf { it > 0 } ?: profile.dailyGoal
            val stride = profile.effectiveStrideMeters
            val progress = (steps.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

            StepUiState(
                todaySteps = steps,
                dailyGoal = dailyGoal,
                goalProgress = progress,
                distanceKm = StepCalculationEngine.calculateDistanceKm(steps, stride),
                caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(steps, profile.weightKg),
                activeMinutes = StepCalculationEngine.calculateActiveMinutes(steps),
                isPaused = profile.isPaused,
                heightCm = profile.heightCm,
                weightKg = profile.weightKg,
                isMale = profile.isMale,
                customStrideMeters = profile.customStrideLengthMeters,
                effectiveStrideMeters = stride,
                appTheme = profile.appTheme,
                recentRecords = recentList,
                weeklyRecords = recentList.take(7),
                monthlyRecords = recentList.take(30),
                hourlyToday = hourlyList,
                userProfile = profile,
                isTrackingActive = isTrackingActive
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StepUiState()
    )
```

**Step 3: Run tests**
Run: `./gradlew testDebugUnitTest --tests com.nordic.stepcounter.ui.StepViewModelTest`
Expected: PASS

---

### Task 3: Pause/Resume Hardware Batch Synchronization & Notification Sync

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/service/StepSensorManager.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/service/StepCounterService.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
- Test: `app/src/test/kotlin/com/nordic/stepcounter/service/StepServiceTest.kt`

**Step 1: Add FIFO flush capability to `StepSensorManager.kt`**
```kotlin
fun flush(): Boolean {
    return sensorManager?.flush(this) ?: false
}
```

**Step 2: Collect `userProfileFlow` in `StepCounterService` to sync notification immediately**
In `StepCounterService.onCreate()`:
```kotlin
serviceScope.launch {
    repository.userProfileFlow
        .map { it.isPaused to it.dailyGoal }
        .distinctUntilChanged()
        .collect {
            if (isForegroundStarted) {
                updateNotificationForCurrentState()
            }
        }
}
```
When handling `ACTION_PAUSE` or `ACTION_TOGGLE_PAUSE`, call `sensorManager.flush()` before updating state.

**Step 3: Route UI pause toggle through service intent when service is active**
In `MainActivity.kt` or `StepViewModel.kt`, dispatch `ACTION_TOGGLE_PAUSE` intent to `StepCounterService` to coordinate hardware flush and state change.

**Step 4: Run tests**
Run: `./gradlew testDebugUnitTest --tests com.nordic.stepcounter.service.StepServiceTest`
Expected: PASS

---

### Task 4: Missing Biometric & Stride Calibration UI in `ProfileScreen`

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/ProfileScreen.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`

**Step 1: Update `ProfileScreen.kt` with Calibration Controls**
Replace static text with an interactive calibration section featuring:
- Height (cm) adjustment (Number field / Stepper)
- Weight (kg) adjustment (Number field / Stepper)
- Sex selector (Male / Female chips)
- Custom stride length toggle with custom value adjustment
- Dialog or inline sheet for updating `(heightCm, weightKg, isMale, customStrideLengthMeters)`.

**Step 2: Wire `onUpdatePhysicalProfile` in `MainActivity.kt`**
Connect `ProfileScreen` parameter `onUpdatePhysicalProfile = viewModel::updatePhysicalProfile`.

**Step 3: Verify build and compose preview**
Run: `./gradlew compileDebugKotlin`
Expected: SUCCESS

---

### Task 5: `HistoryScreen` Sparse-Data 1-Bar Defect & Calendar Day Alignment

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/HistoryScreen.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/components/SwissBarChart.kt`

**Step 1: Fix calendar day alignment and 0-padding in `HistoryScreen.kt`**
```kotlin
val recordsByDate = remember(state.recentRecords) {
    state.recentRecords.associateBy { it.date }
}
val today = LocalDate.now()

val chartValues = when (selectedTab) {
    0 -> (0..23).map { hour -> state.hourlyToday.firstOrNull { it.hour == hour }?.steps ?: 0 }
    1 -> (6 downTo 0).map { daysAgo ->
        val dateKey = today.minusDays(daysAgo.toLong()).toString()
        recordsByDate[dateKey]?.steps ?: 0
    }
    else -> (29 downTo 0).map { daysAgo ->
        val dateKey = today.minusDays(daysAgo.toLong()).toString()
        recordsByDate[dateKey]?.steps ?: 0
    }
}
```

**Step 2: Constrain bar width in `SwissBarChart.kt`**
Ensure that when few bars exist (e.g. 1 or 2 items), `barWidth` is capped at a sensible maximum (e.g. 48.dp) and centered, rather than stretching across the entire screen.

**Step 3: Run unit tests**
Run: `./gradlew test`
Expected: SUCCESS

---

### Task 6: Android 15 Edge-to-Edge System Bar Contrast

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/theme/Theme.kt`

**Step 1: Implement `WindowCompat.getInsetsController` in `NordicStepTheme`**
```kotlin
val view = LocalView.current
if (!view.isInEditMode) {
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !appTheme.isDark
        insetsController.isAppearanceLightNavigationBars = !appTheme.isDark
    }
}
```

**Step 2: Run tests**
Run: `./gradlew testDebugUnitTest --tests com.nordic.stepcounter.ui.theme.ThemeTest`
Expected: PASS

---

### Task 7: Configuration Change State Loss & Missing Sensor Defensive Stop

**Files:**
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/HistoryScreen.kt`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/service/StepCounterService.kt`

**Step 1: Use `rememberSaveable` for UI state**
- `MainActivity.kt`: `var currentTab by rememberSaveable { mutableIntStateOf(0) }`
- `HistoryScreen.kt`: `var selectedTab by rememberSaveable { mutableIntStateOf(0) }`

**Step 2: Prevent duplicate permission dialog on rotation in `MainActivity.kt`**
Only trigger permission requests if permissions are not already granted.

**Step 3: Handle missing sensor gracefully in `StepCounterService.kt`**
If `!sensorManager.isSensorAvailable` or `!sensorManager.startListening()`, cleanly stop service:
```kotlin
if (!sensorManager.startListening()) {
    stopSelf()
    return
}
```

---

### Task 8: Build, Security & Hygiene Improvements

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: Vector drawables for `ic_tab_today.xml`, `ic_tab_history.xml`, `ic_tab_profile.xml` in `app/src/main/res/drawable/`
- Modify: `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
- Modify: `app/src/test/kotlin/com/nordic/stepcounter/service/StepServiceTest.kt`

**Step 1: Set `android:exported="false"` on `BootReceiver`** in `AndroidManifest.xml`.
**Step 2: Remove `kotlin.serialization` plugin** from `app/build.gradle.kts` and `libs.versions.toml`.
**Step 3: Replace `material-icons-extended` dependency** with 3 lightweight vector XML drawables in `res/drawable/`. Remove `androidx-compose-material-icons` from `libs.versions.toml` and `app/build.gradle.kts`.
**Step 4: Clean up duplicate alias methods & properties** in `StepViewModel`, `ProfilePreferences`, `UserProfile`, and `Theme.kt`.
**Step 5: Add `StepRepository.resetInstance()`** to `StepServiceTest.tearDown()`.

**Step 6: Run full test suite & lint verification**
Run: `./gradlew test`
Expected: ALL TESTS PASS
