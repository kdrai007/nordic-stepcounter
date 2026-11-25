## Executive Summary

**Nordic Step Counter** is a well-structured, clean-room Android pedometer that achieves its core goals: **zero network permissions**, **zero third-party trackers/ads**, **low-power hardware sensor batching**, and a **distinctive Swiss/Nordic UI** with 8 custom color themes.

| Category | Rating | Summary |
| :--- | :---: | :--- |
| **Privacy & Security** | **9.5 / 10** | No `INTERNET` or GPS permissions; all data stays in app-private storage. Minor `BootReceiver` export flag issue. |
| **Battery & Sensor Design** | **8.5 / 10** | Proper use of `Sensor.TYPE_STEP_COUNTER` (type 19) with 60s hardware FIFO batching (`60_000_000` µs). Needs sensor flush on Pause/Resume. |
| **Architecture & Concurrency** | **7.5 / 10** | Clean UDF pattern with Room + DataStore Flows, but suffers from a `StepRepository` split-brain instantiation and a stale date in `StepViewModel`. |
| **UI / UX & Android 15 Readiness** | **7.0 / 10** | Great typography and palette system, but biometrics cannot be edited in the UI, `HistoryScreen` misrenders sparse days, and status bar icons don't adapt to Light/Dark themes on Android 15. |
| **Testing** | **9.0 / 10** | Comprehensive unit and Robolectric tests across domain, DAO, DataStore, repository, service, and ViewModel layers. |

---

## 1. Key Strengths

1. **Exemplary Hardware Sensor Efficiency (`StepSensorManager.kt`):**
   Exclusively using `Sensor.TYPE_STEP_COUNTER` with `BATCH_LATENCY_US = 60_000_000` (60 seconds) allows the device's low-power Sensor Hub DSP to buffer steps while the AP/CPU sleeps, avoiding the severe battery drain of `TYPE_ACCELEROMETER` or unbatched `TYPE_STEP_DETECTOR` wakeups.
2. **Android 14/15 (`targetSdk 35`) Foreground Service Compliance (`StepCounterService.kt`, `BootReceiver.kt`):**
   Both `BootReceiver` and `StepCounterService` explicitly guard `startForegroundService()` and `startForeground(..., FOREGROUND_SERVICE_TYPE_HEALTH)` behind `hasActivityRecognitionPermission()`, preventing `SecurityException` and `ForegroundServiceStartNotAllowedException` crashes on Android 14+.
3. **Pure, Testable Domain Logic (`StepDeltaProcessor.kt`, `StepCalculationEngine.kt`):**
   Keeping sensor baseline resolution (first-reading initialization, reboot detection, pause handling, `Int.MAX_VALUE` clamping, and `NaN`/`Infinity` guards) in pure Kotlin objects makes the math deterministic and easy to test.
4. **Zero-Boilerplate Theme Engine (`Color.kt`, `Theme.kt`):**
   Providing `StepColorPalette` via `staticCompositionLocalOf` (`LocalStepPalette`) alongside Material 3 `ColorScheme` makes switching between all 8 dark/light themes instantaneous and clean.

---

## 2. Critical Bugs & Functional Issues (Must Fix)

### 2.1. Midnight Rollover Bug in `StepViewModel` (UI Freezes on Yesterday's Date)
In `StepViewModel.kt` (lines 18–23):
```kotlin
class StepViewModel(
    private val repository: StepRepository,
    currentDateProvider: () -> String = { LocalDate.now().toString() }
) : ViewModel() {

    private val todayDate = currentDateProvider()
```
* **The Bug:** `todayDate` is evaluated **once** when `StepViewModel` is created. If the user keeps the app alive in the background across midnight, `StepCounterService` will start writing steps to the new date (`LocalDate.now()`), while `StepViewModel` continues observing `repository.getDailyRecordFlow(todayDate)` and `getHourlyRecordsFlow(todayDate)` for **yesterday**. The UI will appear frozen until the Activity/process is recreated.
* **Secondary Issue:** In `StepViewModel.init`, `repository.initializeTodayRecord()` is called without passing `todayDate`, bypassing `currentDateProvider`.
* **Fix:** Emit the current date via a `Flow` (or observe `profile.lastRecordedDate` / a periodic date ticker) and use `flatMapLatest` so the ViewModel automatically switches to the new day's Room flows at midnight.

---

### 2.2. Pause/Resume Race Condition with 60-Second Sensor Batching & Notification Desync
Look at how pausing works across `TodayScreen.kt`, `StepViewModel.kt`, `StepCounterService.kt`, and `StepSensorManager.kt`:
1. **Uncounted Steps Before Pause / Counted Steps During Pause:**
   Because `StepSensorManager` batches hardware events in 60-second windows, toggling `isPaused` in DataStore without flushing the hardware sensor FIFO queue causes steps taken *before* pressing Pause (still sitting in the hardware batch buffer) to arrive *after* `isPaused == true` and get discarded. Conversely, when pressing **Resume**, steps taken while paused that haven't been flushed yet arrive *after* `isPaused == false` and get added to the user's daily step count.
2. **Notification Desynchronization:**
   When the user taps **PAUSE TRACKING** on `TodayScreen`, `viewModel.togglePause()` updates DataStore (`repository.setPaused(!currentPaused)`), but **`StepCounterService` is never notified**. Until the next sensor event fires, the ongoing system notification still displays `"X% of 6000 goal"` with a `"Pause"` button even though tracking is paused.
* **Fix:**
  * Either route pause/resume actions through `StepCounterService` (`ACTION_TOGGLE_PAUSE`) or have `StepCounterService` collect `repository.isPausedFlow` to immediately update the notification and call `sensorManager.flush(this)` before applying the state transition.

---

### 2.3. `StepRepository` Singleton Split-Brain
In `StepCounterService.kt` (line 53), the service uses the singleton:
```kotlin
repository = StepRepository.getInstance(this)
```
However, in `MainActivity.kt` (lines 40–44), the Activity instantiates a **separate** `StepRepository`:
```kotlin
private val viewModel: StepViewModel by viewModels {
    val db = StepDatabase.getInstance(applicationContext)
    val prefs = ProfilePreferences.getInstance(applicationContext)
    StepViewModel.Factory(StepRepository(db.stepDao(), prefs))
}
```
* **The Bug:** `StepRepository` holds an instance-level `private val sensorMutex = Mutex()`. Having two separate instances in the same process means `MainActivity` and `StepCounterService` do not share the repository instance. Furthermore, `initializeTodayRecord()` and `updateDailyGoal()` in `StepRepository` perform read-then-upsert operations on `daily_steps` outside `sensorMutex`, which can race with `processHardwareSensorReading()`.
* **Fix:** Use `StepViewModel.provideFactory(applicationContext)` (which already exists in `StepViewModel.Companion` and calls `StepRepository.getInstance(context)`) in `MainActivity`, and wrap `initializeTodayRecord()` and `updateDailyGoal()` inside `sensorMutex.withLock`.

---

### 2.4. Missing Biometric Calibration UI in `ProfileScreen.kt`
`ProfilePreferences`, `StepRepository`, and `StepViewModel` all implement `updatePhysicalProfile(heightCm, weightKg, isMale, customStrideMeters)`. However, `ProfileScreen.kt` (lines 72–82) only renders static text:
```kotlin
Text(
    text = "Height: ${state.heightCm.toInt()} cm • Weight: ${state.weightKg.toInt()} kg • Stride: ${"%.2f".format(state.effectiveStrideMeters)} m",
    style = MaterialTheme.typography.titleMedium,
    color = palette.mutedText
)
```
* **The Impact:** Users have no way to change their height, weight, biological sex, or custom stride length anywhere in the app—everyone is locked to the defaults (`175 cm`, `70 kg`, `Male`, `0.73 m` stride).
* **Fix:** Add an edit dialog or inline sliders/steppers in `ProfileScreen` wired to `viewModel::updatePhysicalProfile`.

---

## 3. UI/UX & Android 15 (`targetSdk 35`) Issues

### 3.1. `HistoryScreen` Sparse-Data Chart Bug & Unfiltered Summary Cards
In `HistoryScreen.kt` (lines 30–42):
```kotlin
val chartValues = when (selectedTab) {
    0 -> (0..23).map { hour -> state.hourlyToday.firstOrNull { it.hour == hour }?.steps ?: 0 }
    1 -> state.recentRecords.take(7).reversed().map { it.steps }
    else -> state.recentRecords.take(30).reversed().map { it.steps }
}
```
1. **Single Giant Bar on New Installs:** On Day 1, `state.recentRecords` contains only 1 item. Selecting `7 DAYS` or `30 DAYS` passes a 1-element list to `SwissBarChart`, which divides the canvas width by `barCount = 1` and draws a single full-width block across the entire screen.
2. **Skipped Calendar Days:** If the device was off for a day, `recentRecords.take(7)` takes the last 7 database rows rather than the last 7 calendar dates, shifting the timeline without showing `0` for missed days.
   * **Fix:** Generate the exact date range `(6 downTo 0).map { LocalDate.now().minusDays(it.toLong()).toString() }` (and `29 downTo 0` for 30 days) and lookup each date in `state.recentRecords`, defaulting to `0`.

### 3.2. Android 15 Edge-to-Edge Status Bar Contrast
Because `targetSdk = 35` enforces edge-to-edge on Android 15, switching between the 4 Dark themes (`NORDIC_DARK`, `GRUVBOX_DARK`, etc.) and 4 Light themes (`NORDIC_LIGHT`, `GRUVBOX_LIGHT`, etc.) does not update the system status bar or navigation bar icon luminance. On light themes like `NORDIC_LIGHT` (`#F8F9FA`), light status bar icons become unreadable.
* **Fix:** Inside `NordicStepTheme` (in `Theme.kt`), update `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !appTheme.isDark`.

### 3.3. State Loss & Repeated Permission Launches on Configuration Change
In `MainActivity.kt` and `HistoryScreen.kt`:
* `var currentTab by remember { mutableIntStateOf(0) }` (`MainActivity.kt:50`) and `var selectedTab by remember { mutableIntStateOf(0) }` (`HistoryScreen.kt:28`) use `remember` instead of `rememberSaveable`. Rotating the device resets navigation back to the `TODAY` tab and resets the history filter.
* `LaunchedEffect(Unit)` in `MainActivity.kt:58` re-triggers `permissionLauncher.launch(...)` on every screen rotation.

### 3.4. Zombie Foreground Service When Hardware Sensor Is Missing
In `StepCounterService.kt` (line 115):
```kotlin
sensorManager.startListening()
```
If a device lacks `Sensor.TYPE_STEP_COUNTER` (e.g., emulators or sideloaded APKs on unsupported devices), `sensorManager.startListening()` returns `false`, yet `StepCounterService` remains in the foreground indefinitely showing `0 STEPS`. If `!sensorManager.isSensorAvailable`, the service should stop itself cleanly and update `_isTrackingActive` in the UI.

---

## 4. Build, Code Hygiene & Security Improvements

1. **Drop `androidx.compose.material:material-icons-extended` (`libs.versions.toml` line 25):**
   The project includes the full `material-icons-extended` artifact solely for 3 icons (`DirectionsWalk`, `BarChart`, `Person`). In debug builds (and unshrunk configurations), `material-icons-extended` adds ~30 MB of bytecode/resources and slows down compilation. Replace those 3 icons with lightweight vector drawables (`res/drawable/ic_*.xml`) and remove the dependency.
2. **Remove Unused `kotlin-serialization` Plugin (`app/build.gradle.kts` line 4):**
   `alias(libs.plugins.kotlin.serialization)` is applied in Gradle, but `kotlinx.serialization` is not used anywhere in the project.
3. **Set `android:exported="false"` on `BootReceiver` (`AndroidManifest.xml` line 47):**
   `BootReceiver` only listens to system broadcasts (`BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`). Setting `android:exported="false"` prevents other apps on the device from sending an explicit spoofed `BOOT_COMPLETED` intent to `BootReceiver`.
4. **Consolidate Duplicate Alias Methods & Properties:**
   Several classes define duplicate pass-through aliases that add noise to the public API:
   * `UserProfile`: `effectiveStrideLengthMeters` vs `effectiveStrideMeters`
   * `ProfilePreferences`: `updatePhysicalProfile` vs `updateProfile`, `updateLastSensorReading` vs `updateLastSensorStepValue`
   * `StepViewModel`: `updateDailyGoal` vs `updateGoal`, `updatePhysicalProfile` vs `updateProfile`, `updateAppTheme` vs `selectTheme`
   * `StepUiState`: `appTheme` vs `currentTheme`
   * `Theme.kt`: `NordicStepTheme` vs `NordicStepCounterTheme`
5. **Reset `StepRepository` Singleton in `StepServiceTest`:**
   `StepCounterService` calls `StepRepository.getInstance(this)`, which caches a static `INSTANCE` in `StepRepository.Companion`. Add `StepRepository.resetInstance()` in `StepServiceTest.tearDown()` so test runs cannot leak repository state across test cases.

---

## 5. Concrete Refactoring Examples

### Fix 1: Fixed Date Rollover & `HistoryScreen` 7/30-Day Padding
In `HistoryScreen.kt`, pad missing calendar days with `0` so 7-day and 30-day charts always render 7 or 30 proportional bars:

```kotlin
val recordsByDate = remember(state.recentRecords) {
    state.recentRecords.associateBy { it.date }
}
val today = remember(state.recentRecords) {
    state.recentRecords.maxOfOrNull { it.date }?.let(LocalDate::parse) ?: LocalDate.now()
}

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

### Fix 2: Sync System Status/Navigation Bars with the 8 Themes (`Theme.kt`)
```kotlin
val view = LocalView.current
if (!view.isInEditMode) {
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !appTheme.isDark
        controller.isAppearanceLightNavigationBars = !appTheme.isDark
    }
}
```

### Fix 3: Synchronize Pause/Resume with `StepCounterService` (`MainActivity.kt`)
Instead of only mutating DataStore from the UI when the user clicks Pause/Resume, dispatch `ACTION_TOGGLE_PAUSE` to `StepCounterService` (or observe `isPausedFlow` inside `StepCounterService`) so the foreground notification and sensor flush stay in lockstep:

```kotlin
// Inside StepCounterService.onCreate():
serviceScope.launch {
    repository.userProfileFlow
        .map { it.isPaused to it.dailyGoal }
        .distinctUntilChanged()
        .collect { (isPaused, goal) ->
            if (isForegroundStarted) {
                val today = LocalDate.now().toString()
                val steps = repository.getDailyRecordDirect(today)?.steps ?: 0
                updateNotification(steps, goal, isPaused)
            }
        }
}
```
