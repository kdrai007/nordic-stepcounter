Here is a thorough review of the **Nordic Step Counter** codebase. Overall, the project has a clean structure (MVI/UDF layering, Room + DataStore, Compose themes, and a solid baseline of unit tests), **but there are several critical concurrency, sensor-handling, and persistence bugs that directly cause severe step-counting inaccuracies** (both massive phantom step spikes and missing/misattributed steps), along with Android 14/15 foreground service crash risks and UI/analytics bugs.

---

# Part 1: Root Causes of Inaccurate Step Counting (Critical)

### 1. Out-of-Order Sensor Processing Triggers False Reboots & Massive Phantom Step Spikes
* **Where:** `StepCounterService.kt` (`serviceScope`, lines 64, 80–86) + `StepDeltaProcessor.kt` (lines 42–51)
* **What happens:**
  `StepSensorManager` registers with 60-second FIFO batching (`BATCH_LATENCY_US = 60_000_000`). When a batch flushes, Android delivers multiple `onSensorChanged` events back-to-back on the main thread (e.g., `10050`, `10065`, `10080`). For **each** event, `StepCounterService` launches an independent coroutine on multi-threaded `Dispatchers.IO`:
  ```kotlin
  sensorManager = StepSensorManager(this) { rawSteps ->
      lastSensorJob = serviceScope.launch { // Dispatchers.IO: no FIFO ordering!
          val updatedDaily = repository.processHardwareSensorReading(rawSteps)
          ...
      }
  }
  ```
  Although `StepRepository` locks with `sensorMutex`, coroutines on `Dispatchers.IO` race to acquire it out of order. If `10080` acquires `sensorMutex` before `10050`:
  1. `10080` updates `lastSensorStepValue = 10080` (+80 steps).
  2. `10050` then runs with `currentSensorSteps = 10050 < lastSensorSteps = 10080`.
  3. `StepDeltaProcessor.computeDelta` treats **any** `currentSensorSteps < lastSensorSteps` as a device reboot and returns `rawDelta = currentSensorSteps` — **adding `+10,050` phantom steps instantly**.
* **Fix:**
  Serialize **all** sensor events and pause/resume commands through a single FIFO queue — e.g., an unbounded `Channel<ServiceEvent>(Channel.UNLIMITED)` consumed by a single coroutine in `StepCounterService` (eliminating both out-of-order execution and the broken `lastSensorJob` tracking).

---

### 2. Unreliable Reboot Detection & Missing `NaN`/`Infinity` Validation
* **Where:** `StepDeltaProcessor.kt` (lines 42–46), `StepSensorManager.kt` (line 106)
* **Problems:**
  1. **False positives:** Any out-of-order event or minor sensor-hub dip triggers `currentSensorSteps < lastSensorSteps` and adds the entire boot cumulative counter as new steps.
  2. **False negatives (lost steps):** If the device actually reboots and the user walks more steps than `lastSensorSteps` before the service receives an event (e.g., `lastSensorSteps = 50`, reboot, user walks `200` steps), `200 >= 50` computes `200 - 50 = 150` instead of `200`, **losing 50 steps**.
  3. **`Float.NaN` / `Infinity` hazard:** In `StepSensorManager.kt:106`:
     ```kotlin
     val totalStepsSinceBoot = event.values.getOrNull(0)?.toLong() ?: return
     ```
     In Kotlin/JVM, `Float.NaN.toLong()` returns `0L` (not `null` or negative). If a malformed reading arrives when `lastSensorSteps = 20000`, `0L < 20000L` resets `lastSensorSteps` to `0L`; the very next valid reading (`20010`) then adds **`+20,010` phantom steps**. `Float.POSITIVE_INFINITY.toLong()` returns `Long.MAX_VALUE`, adding `Int.MAX_VALUE` steps.
* **Fix:**
  * Validate sensor values in `StepSensorManager`:
    ```kotlin
    val rawValue = event.values.getOrNull(0) ?: return
    if (!rawValue.isFinite() || rawValue < 0f) return
    val totalStepsSinceBoot = rawValue.roundToLong()
    ```
  * Persist `Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)` (`minSdk` is 26; `BOOT_COUNT` was added in API 24) alongside `lastSensorSteps`:
    * If `currentBootCount != lastBootCount`: a real reboot occurred → `stepsToAdd = currentSensorSteps` (unless paused).
    * If `currentBootCount == lastBootCount` and `currentSensorSteps < lastSensorSteps`: it is a stale/out-of-order reading (or rare sensor-hub reset) → do **not** add `currentSensorSteps` blindly (ignore small regressions; keep baseline monotonic unless reset near 0).

---

### 3. Always-On 60-Second Batching Without Foreground Flushing or Initial Baseline Flush
* **Where:** `StepSensorManager.kt` (lines 55–60, 117), `StepCounterService.kt` (lines 105–109, 147–169), `MainActivity.kt` (lines 99–104)
* **Problems:**
  1. **Lost initial steps after install:** When `lastSensorStepValue == -1L`, `startTracking()` registers with `BATCH_LATENCY_US = 60_000_000` and never calls `flush()`. On many devices, the initial cumulative reading isn't delivered until the first batch arrives 60s later — so steps walked during that first minute get folded into the baseline (`stepsToAdd = 0`) and are **lost**.
  2. **Stale counts when opening the app:** `MainActivity` calls `startStepServiceIfPermitted()` on `ON_RESUME`, which sends an intent with `action == null` to `StepCounterService`. When `isForegroundStarted == true`, `onStartCommand` hits `else -> {}` and **does nothing** — it never flushes the hardware FIFO. Users see up to 60+ seconds of stale data (or far longer after Doze mode).
  3. **Frozen UI while walking with the app open:** Because 60-second latency stays active even while `TodayScreen` is visible on screen, walking around with the app open shows no movement for up to a full minute.
* **Fix:**
  * Call `sensorManager.flush()` immediately after `startListening()` succeeds.
  * Add an `ACTION_FLUSH` / `ACTION_SET_FOREGROUND_MODE` (or bind to `StepCounterService` while `MainActivity` is started):
    * While the UI is visible: re-register with `maxReportLatencyUs = 0` (immediate delivery) and call `flush()`.
    * When the UI goes to background: re-register with `BATCH_LATENCY_US = 60_000_000` (if `stepCounterSensor.fifoMaxEventCount > 0`) for battery savings.

---

### 4. Ignored `event.timestamp` and Ignored `lastRecordedDate` Cause Cross-Day / Downtime Step Dumping
* **Where:** `StepSensorManager.kt` (lines 104–109), `StepRepository.kt` (lines 88–104)
* **What happens:**
  1. `StepSensorManager` discards `event.timestamp` (nanoseconds since boot when the step actually happened), and `StepRepository` always attributes steps to `LocalDate.now()` / `LocalTime.now().hour` (delivery time). Steps buffered across midnight or Doze get attributed to the wrong hour or the next day.
  2. `lastRecordedDate` is written to DataStore in `StepRepository.kt:101`, **but never read anywhere in production code**. If the service is killed by an OEM battery optimizer on Monday evening (`lastSensorStepValue = 10,000`), the user walks 15,000 steps on Tuesday, and reopens the app on Wednesday morning (`rawSensorSteps = 25,000`), **all 15,000 steps from Tuesday are dumped into Wednesday at the current hour**.
* **Fix:**
  * Pass `event.timestamp` from `StepSensorManager` and convert it to wall-clock time:
    ```kotlin
    val eventTimeMs = System.currentTimeMillis() -
        (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000L
    ```
    Derive `date` and `hour` from `Instant.ofEpochMilli(eventTimeMs).atZone(ZoneId.systemDefault())`.
  * Compare `profile.lastRecordedDate` with `eventDate` (and track `lastEventTimestampMs`). If `lastRecordedDate` is non-empty and earlier than `eventDate` by >1 day (or after long service downtime), avoid silently attributing multi-day offline steps to today's count (either reset the baseline to today or cap/attribute carefully and surface a clear note).

---

### 5. Pause / Resume Race Conditions & Missed Baseline Sync
* **Where:** `StepCounterService.kt` (lines 113–146), `StepSensorManager.kt` (lines 85–102)
* **Problems:**
  1. **`lastSensorJob` overwrites earlier jobs:** `lastSensorJob?.join()` only awaits the *most recently launched* coroutine. Earlier queued FIFO events can still run *after* `setPaused(false)` executes, counting paused steps AND triggering the out-of-order fake reboot bug.
  2. **Cold-start resume counts paused steps:** If the service was killed while `isPaused == true` and the user taps Resume (`ACTION_TOGGLE_PAUSE`), `startTracking()` registers the listener and `ACTION_TOGGLE_PAUSE` immediately calls `flushAndWait()` -> `setPaused(false)` *before* the initial sensor event arrives. When the initial reading arrives milliseconds later with `isPaused == false`, all steps taken while paused are added to today's total.
  3. **Unserialized rapid toggles:** Double-tapping the Pause/Resume button launches two concurrent `ACTION_TOGGLE_PAUSE` coroutines that can both read the same `profile.isPaused` before either writes it.
  4. **`flushAndWait()` hides timeouts:** `StepSensorManager.kt:91-97` discards the `null` return of `withTimeoutOrNull(timeoutMs)` and returns `initiated` (`true`) even when the flush timed out without completing. Also, if `stopListening()` is called during `flushAndWait()`, `flushDeferred` is never completed and stalls for the full 500ms timeout.
* **Fix:**
  * Process sensor events and `PAUSE`/`RESUME`/`TOGGLE_PAUSE` commands through the same sequential `Channel` actor.
  * For Resume: wait for an actual fresh baseline sensor reading (or re-register/flush and wait for completion) before clearing `isPaused`, or store a `pendingResumeBaseline` flag so the first reading after resume sets the baseline without adding delta.
  * Return `withTimeoutOrNull(timeoutMs) { deferred.await() } != null` from `flushAndWait()`, and cancel/complete `flushDeferred` in `stopListening()`.

---

### 6. Non-Atomic Split-Brain Writes Between DataStore & Room + Abrupt `serviceScope.cancel()`
* **Where:** `StepRepository.kt` (lines 100–137), `StepCounterService.kt` (lines 238–244)
* **What happens:**
  `processHardwareSensorReading` writes `lastSensorStepValue` to **DataStore first**, and then writes `daily_steps` / `hourly_steps` to **Room**. In `StepCounterService.onDestroy()`, `serviceScope.cancel()` immediately cancels any in-flight coroutine. If cancellation or process death happens between those two writes, the baseline has already advanced while Room was never updated — **permanently losing those steps**.
* **Fix:**
  Move volatile sensor tracking state (`lastSensorStepValue`, `lastBootCount`, `lastRecordedDate`, `isPaused`) into a single-row Room entity (`SensorStateEntity`) and update sensor state + `DailyStepEntity` + `HourlyStepEntity` inside a **single `@Transaction`** in `StepDao`. This guarantees 100% atomicity and also cleanly solves the backup bug in #9 below.

---

# Part 2: Crashes, Concurrency & Android Lifecycle Bugs (High Severity)

### 7. Android 12–15 Foreground Service Crash Risks
* **Where:** `BootReceiver.kt` (line 23), `MainActivity.kt` (lines 139, 169), `StepCounterService.kt` (lines 71–75, 100–112, 179–192, 209)
* **Issues:**
  1. **`ForegroundServiceDidNotStartInTimeException`:** If `StepCounterService` is launched via `startForegroundService()` and permission was revoked in the meantime (or `ACTION_STOP` is sent before foreground promotion), `onCreate()`/`onStartCommand()` calls `stopSelf()` **without** ever calling `startForeground()`. Android throws a fatal `ForegroundServiceDidNotStartInTimeException`.
  2. **Uncaught `ForegroundServiceStartNotAllowedException` / `SecurityException`:** Neither `BootReceiver.onReceive()`, `MainActivity.startStepServiceIfPermitted()`, nor `StepCounterService.promoteToForeground()` wraps FGS startup in `try/catch`. On Android 14/15 (`targetSdk = 35`), background FGS starts from `BOOT_COMPLETED` (e.g., if the user put the app in the Restricted battery bucket or revoked permissions) or transitional Activity states can throw these exceptions and crash the app.
  3. **Notification action uses `PendingIntent.getService`:** `StepCounterService.kt:209` uses `PendingIntent.getService(...)`, which throws `BackgroundServiceStartNotAllowedException` on Android 12+ if the service is not currently running. Since `minSdk = 26`, use `PendingIntent.getForegroundService(...)`.
  4. **Duplicate `MainActivity` stack on notification tap:** `openAppIntent` (`StepCounterService.kt:201`) doesn't set `Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP` (and `AndroidManifest.xml` has no `android:launchMode="singleTop"`), pushing a new `MainActivity` onto the back stack every time the user taps the notification.

### 8. Ghost Ongoing Notification After `ACTION_STOP` & Non-Volatile `isForegroundStarted`
* **Where:** `StepCounterService.kt` (lines 69, 80–95, 118, 128, 141, 171–177)
* **What happens:**
  * `isForegroundStarted` is a plain `Boolean` mutated on the main thread and read on `Dispatchers.IO` (thread-visibility hazard).
  * Sensor callbacks and pause/resume coroutines call `updateNotification()` **without checking `isForegroundStarted`**. If `ACTION_STOP` runs `handleStop()` (`STOP_FOREGROUND_REMOVE`) while a coroutine is in flight, that coroutine finishes milliseconds later and calls `notificationManager.notify()`, leaving a permanent `setOngoing(true)` zombie notification after the service has stopped.
* **Fix:** Make `isForegroundStarted` `@Volatile` (or manage all state on a single-threaded dispatcher) and guard `updateNotification()` with `if (!isForegroundStarted) return`.

### 9. Stale Service Notification After Midnight Rollover
* **Where:** `StepCounterService.kt`
* **What happens:** The service never listens for `Intent.ACTION_DATE_CHANGED`, `ACTION_TIME_CHANGED`, or `ACTION_TIMEZONE_CHANGED`. At midnight, `StepViewModel` rolls over to `0` steps while the persistent notification continues displaying yesterday's total until the next sensor batch arrives.
* **Fix:** Register a dynamic `BroadcastReceiver` in `StepCounterService` for `ACTION_DATE_CHANGED`, `ACTION_TIME_CHANGED`, and `ACTION_TIMEZONE_CHANGED` to initialize the new day's record and refresh the notification.

### 10. `MainActivity` Re-Triggers Permission Prompt / System Settings on Every Configuration Change
* **Where:** `MainActivity.kt` (lines 58, 106–110)
* **What happens:**
  ```kotlin
  LaunchedEffect(Unit) {
      if (!hasPermission) {
          requestPermissionsAction()
      }
  }
  ```
  `LaunchedEffect(Unit)` re-runs on every configuration change (screen rotation, dark mode toggle, foldable unfold). Because `hasRequestedPermissionOnce` is saved with `rememberSaveable`, rotating the phone after permanently denying permission immediately yanks the user out of the app into **Android System Settings** (or re-prompts on every rotation).
  Additionally, `POST_NOTIFICATIONS` permission denial on Android 13+ is never checked or surfaced in the UI, silently hiding the foreground service notification.
* **Fix:** Guard the initial auto-request with `if (!hasPermission && !hasRequestedPermissionOnce)` (or only launch automatically once per session).

### 11. Backup Rules Wipe All User Profile & Theme Settings on Restore / Device Transfer
* **Where:** `backup_rules.xml`, `data_extraction_rules.xml`, `ProfilePreferences.kt`
* **What happens:** Both backup configs exclude `datastore/nordic_user_preferences.preferences_pb` (smartly preventing stale hardware `lastSensorStepValue` from migrating to a new device). However, because user height, weight, sex, custom stride, daily goal, and theme live in the **same** DataStore file, all user preferences are wiped on backup/restore while Room step history is preserved.
* **Fix:** Separate hardware sensor tracking state into its own storage (or into a Room table validated against `BOOT_COUNT` / installation ID) and include user profile preferences in backups.

---

# Part 3: Analytics, UI/UX, Accessibility & Theme Issues (Medium Severity)

### 12. Misleading Statistics & Missing Labels in `HistoryScreen` / `SwissBarChart`
* **Where:** `HistoryScreen.kt` (lines 38–99), `SwissBarChart.kt`, `StepDao.kt` (line 18)
* **Issues:**
  1. **"PERSONAL BEST" is only a 30-day best:** It computes `state.recentRecords.maxOfOrNull { it.steps }`, where `recentRecords` is `LIMIT 30`. An all-time record from 31 days ago vanishes. Add `@Query("SELECT MAX(steps) FROM daily_steps") fun getBestDaySteps(): Flow<Int?>` in `StepDao`.
  2. **"DAILY AVG" ignores the selected tab & is skewed:** Switching between `24 HOURS`, `7 DAYS`, and `30 DAYS` never updates `DAILY AVG`. It also averages only existing DB rows (ignoring skipped days, yet including today's incomplete `0`-step row created at midnight).
  3. **Future-date pollution:** `SELECT * FROM daily_steps ORDER BY date DESC LIMIT :limit` can be displaced if the device clock ever jumped forward. Query an explicit date range (`WHERE date BETWEEN :startDate AND :endDate`).
  4. **Unlabeled bar chart:** `SwissBarChart` has no X-axis labels (`0h/6h/12h/18h`, weekday initials, or dates), no Y-axis scale / goal reference line, and no tap inspection to see exact step counts per bar.

### 13. Non-Scrollable Screens & Text Overflow on Compact/Landscape/Scaled-Font Devices
* **Where:** `TodayScreen.kt`, `HistoryScreen.kt`, `RadialProgressGauge.kt`, `MetricStatCard.kt`, `ProfileScreen.kt`
* **Issues:**
  * `TodayScreen` and `HistoryScreen` use non-scrollable `Column(Modifier.fillMaxSize())` — in landscape, split-screen, or with Android accessibility font scaling (1.5x–2.0x), the `PauseResumeButton`, permission banner, and stat cards clip off-screen.
  * `RadialProgressGauge` renders `56.sp` Monospace bold text inside a `212.dp` inner circle: 6–7 digit values (`10,000`–`100,000`) or enlarged system font scales collide with and overflow the ring stroke.
  * `MetricStatCard` (`18.sp`) has no `maxLines = 1` or auto-sizing; on 360dp devices, 3 cards in a row give ~64dp of inner width, causing `"12.34"` / `"1250"` to wrap across two lines.
  * `PauseResumeButton` remains enabled when `!hasStepSensor` or `!hasPermission`, letting users "pause" a non-existent/unpermitted sensor.
  * `ProfileScreen` theme cards in `FlowRow(maxItemsInEachRow = 2)` wrap 2-word/3-word names (`"Tokyo Night Light"`) to 2 lines, creating mismatched card heights. Also, `ProfileScreen` only allows 4 hardcoded goal presets (`6k/8k/10k/12k`) despite `StepRepository` supporting `1,000..100,000`.
  * Custom stride `±0.02f` buttons in `ProfileScreen.kt:330,342` accumulate IEEE-754 floating-point drift (e.g., `0.72625f + 0.02f = 0.74625f` displaying `0.75` while saving `0.74625`). Round to 2 decimals.

### 14. WCAG Contrast Failures Across Multiple Palettes & Dark-to-Light Startup Flash
* **Where:** `Color.kt`, `StepViewModel.kt` (line 88)
* **Contrast issues (WCAG AA requires 4.5:1 for normal/small text):**
  * `TokyoNightDarkPalette`: `mutedText` (`#565F89`) on `surface` (`#24283B`) has a contrast ratio of **~2.35:1** (barely legible for `11.sp` labels, nav items, and dialog titles). Lighten `mutedText` (e.g., `#8C96C0`).
  * `NordicLightPalette`: `accent` (`#00B377`) against `surface` (`#FFFFFF`) / `background` (`#F8F9FA`) has a contrast ratio of **~2.72:1** — affects `"GRANT"`, `"CALIBRATE"`, `"RESUME TRACKING"`, and `"SAVE"` button text. Darken light-mode accent (e.g., `#007A52`).
  * `CatppuccinLightPalette`: `accent` (`#40A02B`) on `surface` (`#E6E9EF`) is **~2.8:1**; `GruvboxLightPalette`: `mutedText` (`#7C6F64`) on `surface` (`#EBDBB2`) is **~3.5:1**.
* **Theme Flash on Launch:** `StepViewModel.uiState` uses `initialValue = StepUiState()` which defaults to `AppTheme.NORDIC_DARK`. Users who selected any light theme see a dark flash on every cold start until DataStore emits. Consider using `androidx.core:core-splashscreen` (`setKeepOnScreenCondition`) until the first DataStore theme emission arrives.

### 15. Hardcoded Strings & Missing Accessibility Semantics
* **Where:** `strings.xml`, `MainActivity.kt`, `TodayScreen.kt`, `HistoryScreen.kt`, `ProfileScreen.kt`, `RadialProgressGauge.kt`, `SwissBarChart.kt`, `StepCounterService.kt`
* All UI and notification copy is hardcoded in English instead of `strings.xml`.
* `RadialProgressGauge` and `SwissBarChart` have no `Modifier.semantics { contentDescription = ... }`, making progress and history charts unreadable to TalkBack screen readers.

### 16. Minor Math / Consistency Bugs
* **`StepCounterService.kt:216`:** `((steps * 100) / goal)` can overflow `Int` if `steps > 21,474,836`. Use `(steps.toLong() * 100L / goal).coerceIn(0L, 100L)`.
* **`StepRepository.kt:117` & `167`:**
  * `activeDurationSeconds = StepCalculationEngine.calculateActiveMinutes(newTotalSteps) * 60L` truncates sub-minute durations (`199` steps → `60s` instead of `119s`).
  * `updatePhysicalProfile` doesn't validate/clamp inputs (`NaN`/negatives) in the data layer and never recalculates stored `DailyStepEntity` distance/calories (whereas `StepViewModel` ignores DB values and recalculates live from `todaySteps`).
* **`StepCalculationEngine.kt:23`:** Calorie estimation (`steps * weightKg * 0.00075f`) ignores stride length (distance covered) and runs slightly high (gross rather than net walking kcal). Using distance (`distanceKm * weightKg * 0.55f`–`0.65f`) reflects height/stride calibration accurately.
* **`StepViewModel.kt:34`:** Polling `LocalDate.now()` in a `while (true) { delay(10_000) }` loop is unnecessary; compute the exact delay until next midnight (plus react to date/timezone changes).

---

# Part 4: Code Quality, Configuration & Testing Gaps (Low Severity)

1. **Empty `proguard-rules.pro` with `isMinifyEnabled = true`:** `ProfilePreferences.kt:82` deserializes themes with `AppTheme.valueOf(it)`. Add explicit keep rules for `AppTheme` (or map via stable string keys rather than `.name`).
2. **Room `exportSchema = false` (`StepDatabase.kt:8`):** Enable `exportSchema = true` before shipping v1 so future migrations (e.g., adding `SensorStateEntity`) can be safely validated with `MigrationTestHelper`.
3. **Incomplete `SwissTypography` (`Type.kt`):** Only `displayLarge`, `titleMedium`, and `labelSmall` are defined; `bodySmall` (used across `TodayScreen` and `ProfileScreen`) falls back to Material defaults.
4. **Dead / Duplicate APIs cluttering maintenance:**
   * Duplicate aliases: `UserProfile.effectiveStrideLengthMeters` / `effectiveStrideMeters`; `ProfilePreferences.updatePhysicalProfile` / `updateProfile`; `StepViewModel.updateDailyGoal` / `updateGoal`, `updatePhysicalProfile` / `updateProfile`, `updateAppTheme` / `selectTheme`; `StepUiState.appTheme` / `currentTheme`.
   * Unused production code: `StepSensorManager.stepEvents` (allocates 64-item buffer, never collected), `StepViewModel._isTrackingActive`/`setTrackingActive` (always `true`), `StepUiState.weeklyRecords`/`monthlyRecords` (never read in `HistoryScreen`), `StepRepository.getTotalLifetimeSteps()` / `todayStepRecordFlow` / `getLast7DaysRecordsFlow` / `getLast30DaysRecordsFlow` / `getHourlyRecordsForTodayFlow`.
   * `StepUiState.effectiveStrideMeters` default (`0.72f`) mismatches `UserProfile().effectiveStrideMeters` (`0.72625f`).
5. **Testing Gaps:**
   * **Missing tests for the exact bugs causing inaccurate steps:** No tests for out-of-order/concurrent sensor bursts in `StepCounterService`, `Float.NaN`/`Infinity` sensor values, `BOOT_COUNT` reboot detection, `ACTION_TOGGLE_PAUSE`, multi-day downtime gaps, or midnight notification updates.
   * **Shared DataStore state in unit tests:** `ProfilePreferencesTest`, `StepRepositoryTest`, and `StepServiceTest` share the singleton `Context.dataStore` file instead of creating isolated temp DataStores per test.
   * **No Compose UI tests (`androidTest`)** despite declaring all Compose UI test dependencies in `app/build.gradle.kts`.

---

# Recommended Fix Priority

1. **Fix step accuracy first (highest impact):**
   * Replace `serviceScope.launch` per sensor event in `StepCounterService` with a single sequential `Channel` actor handling both sensor readings and pause/resume commands.
   * Add `BOOT_COUNT` tracking to `StepDeltaProcessor` and `isFinite() && >= 0f` + `roundToLong()` validation in `StepSensorManager`.
   * Pass `event.timestamp` into `StepRepository`, honor `lastRecordedDate` for multi-day downtime, and move sensor baseline state into a Room table updated in the same `@Transaction` as daily/hourly steps.
   * Flush sensor immediately on `startListening()` and on `MainActivity` `ON_RESUME` (and switch to 0µs latency while UI is visible, 60s when backgrounded).
2. **Eliminate FGS crashes & lifecycle bugs:**
   * Guard all `startForegroundService` / `startForeground` calls with `try/catch`; ensure `startForeground` is called before `stopSelf()` if started via `startForegroundService()`; switch notification action to `PendingIntent.getForegroundService`; guard `updateNotification()` with `@Volatile isForegroundStarted`; add midnight broadcast receiver; fix `MainActivity` rotation permission loop.
3. **Fix analytics, UI overflow & accessibility:**
   * Query true all-time Personal Best and date-ranged averages in `StepDao`; add labels/scrolling to `TodayScreen` & `HistoryScreen`; fix `RadialProgressGauge` text sizing and WCAG palette contrast; separate user preferences from volatile sensor state so backups preserve profile & theme.
