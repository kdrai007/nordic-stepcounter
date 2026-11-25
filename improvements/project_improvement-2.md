# Suggested improvements

### Ground Rules for the Coding Agent (Flash Guardrails)
1. **Never add `android.permission.INTERNET`** or any location/analytics dependency.
2. **Execute one task at a time** and run the specified `./gradlew` command after each task before moving to the next.
3. **Do not delete duplicate alias methods** (`updateGoal`, `updateProfile`, `selectTheme`, `effectiveStrideMeters`, `currentTheme`) unless you update `StepViewModelTest.kt` and `ProfilePreferencesTest.kt` in the same step—existing tests assert them directly.

---

## P0 — Critical Functional & Crash Fixes

### Task 1: Fix Async Race Condition on Sensor FIFO Flush During Pause/Resume
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/service/StepSensorManager.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/service/StepCounterService.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
- **Root Cause:**
  1. `SensorManager.flush(listener)` is **asynchronous**. In `StepCounterService.kt` (`ACTION_PAUSE` / `ACTION_RESUME` / `ACTION_TOGGLE_PAUSE`), `repository.setPaused(...)` executes immediately before the flushed `onSensorChanged` event arrives—so pre-pause steps are discarded and paused steps get counted on resume.
  2. `TodayScreen`'s pause button in `MainActivity.kt` calls `viewModel::togglePause`, bypassing `StepCounterService` completely so `flush()` is never called from the UI.
- **Concrete Changes:**
  1. In `StepSensorManager.kt`:
     - Implement `android.hardware.SensorEventListener2` instead of `SensorEventListener`.
     - Add a `private val flushMutex = Mutex()` and `private var flushDeferred: CompletableDeferred<Unit>? = null`.
     - Override `onFlushCompleted(sensor: Sensor?)` to call `flushDeferred?.complete(Unit)`.
     - Add `suspend fun flushAndWait(timeoutMs: Long = 500L)`: if `sensorManager?.flush(this) == true`, await `withTimeoutOrNull(timeoutMs) { deferred.await() }`, then clear `flushDeferred`.
     - Add a `private var isListening = false` guard in `startListening()` / `stopListening()` to prevent duplicate listener registrations.
  2. In `StepCounterService.kt`:
     - Replace `sensorManager.flush()` in `ACTION_PAUSE`, `ACTION_RESUME`, and `ACTION_TOGGLE_PAUSE` with `sensorManager.flushAndWait()`.
  3. In `MainActivity.kt`:
     - Update `TodayScreen(onTogglePause = ...)` so that if `StepCounterService.hasActivityRecognitionPermission(this)` and a step counter sensor exists, it sends `Intent(this, StepCounterService::class.java).apply { action = StepCounterService.ACTION_TOGGLE_PAUSE }` via `ContextCompat.startForegroundService`; otherwise fall back to `viewModel.togglePause()`.
- **Verify:**
  - Add a test in `StepSensorManagerTest.kt` verifying `flushAndWait()` completes when `onFlushCompleted()` fires and times out safely when it does not.
  - Run: `./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.service.*"`

---

### Task 2: Fix Android 12+ Foreground Service Crash When Sensor Is Unavailable & Double `startTracking()` Call
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/service/StepCounterService.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/service/BootReceiver.kt`
- **Root Cause:**
  1. In `StepCounterService.onStartCommand()`, `startTracking()` is called at line 96 (`if (intent?.action != ACTION_STOP && !isForegroundStarted)`) **and then called a second time** in `when (intent?.action)` for `ACTION_START, null, else`.
  2. If `MainActivity` or `BootReceiver` calls `ContextCompat.startForegroundService()` on a device without `Sensor.TYPE_STEP_COUNTER` (e.g., emulator or unsupported device), `startTracking()` calls `handleStop()` **before** `promoteToForeground()`, which triggers Android 12+'s fatal `ForegroundServiceDidNotStartInTimeException`.
  3. `startTracking()` ignores the boolean return value of `sensorManager.startListening()`.
- **Concrete Changes:**
  1. Add a helper on `StepCounterService.Companion`:
     ```kotlin
     fun hasStepCounterSensor(context: Context): Boolean {
         val sm = context.getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
         return sm?.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null
     }
     ```
  2. Guard `ContextCompat.startForegroundService(...)` in both `MainActivity.startStepServiceIfPermitted()` and `BootReceiver.onReceive()` with `StepCounterService.hasStepCounterSensor(context)`.
  3. In `StepCounterService.onStartCommand()`:
     - Remove the duplicate `startTracking()` calls from `ACTION_START, null, else` (only ensure foreground is started once when `action != ACTION_STOP`).
     - Inside `startTracking()`: always call `promoteToForeground(...)` first if `!isForegroundStarted`, then check `if (!sensorManager.isSensorAvailable || !sensorManager.startListening()) { handleStop(); return }` so Android's FGS contract is never violated even if started externally.
  4. Replace the legacy system notification icon `.setSmallIcon(android.R.drawable.ic_menu_directions)` with `.setSmallIcon(R.drawable.ic_tab_today)` and format the title with `"%,d STEPS".format(steps)`.
  5. Remove unused imports `StepDatabase` and `ProfilePreferences` from `StepCounterService.kt`.
- **Verify:**
  - Add a test in `StepServiceTest.kt` verifying `onStartCommand(ACTION_START)` stops cleanly when no `TYPE_STEP_COUNTER` sensor is present.
  - Run: `./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.service.StepServiceTest"`

---

### Task 3: Fix Midnight Rollover Freeze in `HistoryScreen` & Redundant Init in `StepViewModel`
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/StepUiState.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/StepViewModel.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/HistoryScreen.kt`
- **Root Cause:**
  1. `HistoryScreen.kt:37` caches `val today = remember { LocalDate.now() }`. Even though `StepViewModel` rolls over at midnight, `HistoryScreen` stays pinned to the date the screen first composed.
  2. `StepViewModel.kt` calls `repository.initializeTodayRecord()` inside `init` (ignoring the injected `currentDateFlow`) **and** calls it again inside `currentDateFlow.onEach`.
  3. `StepViewModel.togglePause()` reads `uiState.value.isPaused` instead of `repository.userProfileFlow.first().isPaused`, which can toggle the wrong value before `uiState` settles.
- **Concrete Changes:**
  1. In `StepUiState.kt`, add `val currentDate: String = LocalDate.now().toString()`.
  2. In `StepViewModel.kt`:
     - Set `currentDate = todayDate` inside `combine { ... }`.
     - Remove the redundant `init { viewModelScope.launch { repository.initializeTodayRecord() } }` block.
     - In `togglePause()`, read `val currentPaused = repository.userProfileFlow.first().isPaused`.
  3. In `HistoryScreen.kt`:
     - Replace `val today = remember { LocalDate.now() }` with:
       ```kotlin
       val today = remember(state.currentDate) {
           runCatching { LocalDate.parse(state.currentDate) }.getOrElse { LocalDate.now() }
       }
       ```
     - Fix dead `?: state.dailyGoal` expression on line 77 (`chartValues` is never empty):
       ```kotlin
       val chartMax = when (selectedTab) {
           0 -> (chartValues.maxOrNull() ?: 0).coerceAtLeast(1000)
           else -> maxOf(chartValues.maxOrNull() ?: 0, state.dailyGoal, 1000)
       }
       ```
- **Verify:**
  - Update `StepViewModelTest.kt` to assert `state.currentDate` updates on `dateFlow` rollover.
  - Run: `./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.ui.StepViewModelTest"`

---

### Task 4: Make Daily + Hourly Room Writes Atomic & Guard Overflow/Invalid Goals
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/data/local/StepDao.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/data/repository/StepRepository.kt`
- **Root Cause:**
  1. In `StepRepository.processHardwareSensorReading()`, `stepDao.upsertDaily` and `stepDao.upsertHourly` run as two separate DB writes—a process death between them leaves daily and hourly tables inconsistent.
  2. `val newTotalSteps = baseSteps + deltaResult.stepsToAdd` can overflow `Int` if `stepsToAdd == Int.MAX_VALUE` and `baseSteps > 0`.
  3. `updateDailyGoal(goal)` does not clamp invalid goals (`<= 0`).
- **Concrete Changes:**
  1. In `StepDao.kt`, add:
     ```kotlin
     @Transaction
     suspend fun upsertDailyAndHourly(daily: DailyStepEntity, hourly: HourlyStepEntity?) {
         upsertDaily(daily)
         if (hourly != null) {
             upsertHourly(hourly)
         }
     }
     ```
  2. In `StepRepository.kt`:
     - Compute `val newTotalSteps = (baseSteps.toLong() + deltaResult.stepsToAdd).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()`.
     - Build `hourlyEntity` (or `null` if `deltaResult.stepsToAdd <= 0`) and call `stepDao.upsertDailyAndHourly(updatedDaily, hourlyEntity)`.
     - In `updateDailyGoal(goal: Int)`, clamp `val safeGoal = goal.coerceIn(1_000, 100_000)`.
- **Verify:**
  - Add a test in `StepDaoTest.kt` for `upsertDailyAndHourly` and a test in `StepRepositoryTest.kt` for goal clamping.
  - Run: `./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.data.*"`

---

## P1 — UI, Theming, Battery & Android 15 Polish

### Task 5: Fix Material 3 Default Purple Leak Across All 8 Themes & Enable Edge-to-Edge on API 26–34
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/theme/Theme.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
- **Root Cause:**
  1. `Theme.kt` only populates 6 slots of `darkColorScheme`/`lightColorScheme`. Unset slots (`secondaryContainer`, `onSecondaryContainer`, `onPrimary`, `surfaceVariant`, `onSurfaceVariant`, `surfaceContainer`) fall back to **Material 3 default purple**, causing `NavigationBarItem` indicators and selected `FilterChip`s to clash with Nordic/Gruvbox/Catppuccin/TokyoNight palettes.
  2. `MainActivity.onCreate()` never calls `enableEdgeToEdge()`, causing unstyled system bars on API 26–34.
  3. `MainActivity.kt` uses `collectAsState()` instead of `collectAsStateWithLifecycle()` (even though `lifecycle-runtime-compose` is already in `build.gradle.kts`). With `collectAsState()`, `StepViewModel`'s `SharingStarted.WhileSubscribed(5000)` and 10-second date polling coroutine **keep running while the app is in the background**.
- **Concrete Changes:**
  1. In `Theme.kt`, populate the missing `ColorScheme` tokens from `palette`:
     ```kotlin
     primary = palette.accent,
     onPrimary = palette.background,
     secondary = palette.accent,
     onSecondary = palette.background,
     secondaryContainer = palette.accent.copy(alpha = 0.18f),
     onSecondaryContainer = palette.accent,
     background = palette.background,
     onBackground = palette.primaryText,
     surface = palette.surface,
     onSurface = palette.primaryText,
     surfaceVariant = palette.surface,
     onSurfaceVariant = palette.mutedText,
     surfaceContainer = palette.surface,
     outline = palette.border,
     outlineVariant = palette.border
     ```
  2. In `MainActivity.kt`:
     - Call `enableEdgeToEdge()` before `setContent { ... }`.
     - Replace `viewModel.uiState.collectAsState()` with `viewModel.uiState.collectAsStateWithLifecycle()`.
     - Remove unused imports (`remember`, `StepDatabase`, `ProfilePreferences`, `StepRepository`).
- **Verify:**
  - Run: `./gradlew testDebugUnitTest compileDebugKotlin`

---

### Task 6: Fix `ProfileScreen` Layout Overflow on Small Screens, Button Order, & State Loss on Rotation
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/ProfileScreen.kt`
- **Root Cause:**
  1. Daily goal `FilterChip`s (line 73) are in a single non-wrapping `Row`, which clips `"12,000"` on narrow phones (`360dp`).
  2. Inside the `AlertDialog`, 4 `OutlinedButton`s in a `Row` with default `24.dp` horizontal padding overflow horizontally, and the button order is `-1, -5, +1, +5` instead of `-5, -1, +1, +5`.
  3. `showCalibrationDialog` and `editHeight`/`editWeight`/`editIsMale`/`editCustomStride` use `remember` instead of `rememberSaveable`, discarding edits on screen rotation.
  4. Line 277 duplicates the stride formula (`editHeight * (if (editIsMale) 0.415f else 0.413f) / 100f`) instead of calling `StepCalculationEngine.calculateDefaultStrideLengthMeters(editHeight, editIsMale)`.
- **Concrete Changes:**
  1. Change the goal presets `Row` to `FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp))`.
  2. Replace `remember` with `rememberSaveable` for `showCalibrationDialog`, `editHeight`, `editWeight`, `editIsMale`, and `editCustomStride`.
  3. Reorder stepper buttons to `-5`, `-1`, `+1`, `+5`, use `Modifier.weight(1f)` and `contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)` with `maxLines = 1`. Apply the same compact padding to the Stride row (`Auto`, `-0.02`, `+0.02`).
  4. Replace the duplicated stride formula with `StepCalculationEngine.calculateDefaultStrideLengthMeters(editHeight, editIsMale)`.
- **Verify:**
  - Run: `./gradlew compileDebugKotlin`

---

### Task 7: Add Missing Permission-Denied Banner & Sensor-Unavailable Feedback on `TodayScreen`
- **Files:**
  - `app/src/main/kotlin/com/nordic/stepcounter/MainActivity.kt`
  - `app/src/main/kotlin/com/nordic/stepcounter/ui/screens/TodayScreen.kt`
- **Root Cause:** If the user denies `ACTIVITY_RECOGNITION` (or runs on a device without a hardware step counter), the app silently displays `0` steps forever with no explanation or way to re-grant permission.
- **Concrete Changes:**
  1. In `MainActivity.kt`, track `var hasPermission by remember { mutableStateOf(StepCounterService.hasActivityRecognitionPermission(this)) }` (updating it in the `permissionLauncher` callback and on `ON_RESUME` via `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` so granting from Android Settings is detected immediately), plus `val hasStepSensor = remember { StepCounterService.hasStepCounterSensor(this) }`.
  2. Pass `hasPermission`, `hasStepSensor`, and `onRequestPermission` into `TodayScreen`.
  3. In `TodayScreen.kt`, render a compact 1dp-bordered `Surface` banner above `PauseResumeButton` when `!hasStepSensor` ("No hardware step counter sensor detected on this device") or `!hasPermission` ("Activity permission required to count steps" + `"GRANT"` action button).
- **Verify:**
  - Run: `./gradlew compileDebugKotlin testDebugUnitTest`

---

## P2 — Data Safety & Cleanliness

### Task 8: Prevent Cross-Device Backup Corruption of `lastSensorStepValue`
- **Files:**
  - `app/src/main/AndroidManifest.xml`
  - `app/src/main/res/xml/backup_rules.xml`
  - `app/src/main/res/xml/data_extraction_rules.xml`
- **Root Cause:** `AndroidManifest.xml` has `android:allowBackup="true"` without referencing `backup_rules.xml` / `data_extraction_rules.xml`. Restoring `nordic_user_preferences.preferences_pb` onto a new device carries over the old phone's `lastSensorStepValue`, causing an incorrect first-day step delta.
- **Concrete Changes:**
  1. Either set `android:allowBackup="false"` in `AndroidManifest.xml` (simplest and strongest privacy guarantee for a local-only offline app), **or** wire `android:fullBackupContent="@xml/backup_rules"` and `android:dataExtractionRules="@xml/data_extraction_rules"` and explicitly exclude `datastore/nordic_user_preferences.preferences_pb`.
- **Verify:**
  - Run: `./gradlew assembleDebug`

---

### Task 9: Final Verification Checklist (Run After All Tasks)
```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease
```
Confirm:
1. All unit & Robolectric tests pass (`0` failures).
2. `assembleRelease` succeeds with R8 minification (`isMinifyEnabled = true`) enabled.
3. No `INTERNET` permission exists in the merged manifest (`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`).
