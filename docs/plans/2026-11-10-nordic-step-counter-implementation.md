# Nordic Step Counter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a high-performance, battery-efficient, and tracker-free Android Step Counter app (`com.nordic.stepcounter`) featuring hardware step detection, Room persistence, and a Nordic/Swiss minimalist Jetpack Compose UI.

**Architecture:** Clean Architecture with Unidirectional Data Flow (UDF). A low-power Android `ForegroundService` with `FOREGROUND_SERVICE_HEALTH` binds to hardware `Sensor.TYPE_STEP_COUNTER` (sensor 19). Data is persisted locally in Room SQLite across daily and hourly buckets. `StepViewModel` exposes reactive `StateFlow<StepUiState>` to a typography-first Jetpack Compose UI. Zero `INTERNET` permission ensures absolute privacy.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Android Jetpack (Room, DataStore Preferences, Lifecycle ViewModel), Kotlin Coroutines & Flow, Android Hardware Sensor Hub API, JUnit4, MockK, Robolectric / Kotlin Coroutines Test.

---

### Task 1: Project Scaffolding & Gradle Configuration

**Files:**
- Create: `app/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Scaffold Android project using `android create`**
Run:
```bash
/home/kdrai/.local/bin/android create empty-activity \
  --name="Nordic Step Counter" \
  --application-id="com.nordic.stepcounter" \
  --namespace="com.nordic.stepcounter" \
  --min-sdk=26 \
  --force
```
Expected: Successfully initialized Android project with Gradle wrapper.

**Step 2: Configure dependencies in `app/build.gradle.kts`**
Add Room runtime, Room KTX, Room Compiler (KSP), DataStore Preferences, and testing libraries:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.nordic.stepcounter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nordic.stepcounter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Room Database
    implementation("androidx.room:room-runtime:2.7.0-alpha13")
    implementation("androidx.room:room-ktx:2.7.0-alpha13")
    ksp("androidx.room:room-compiler:2.7.0-alpha13")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.room:room-testing:2.7.0-alpha13")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
```

**Step 3: Verify build tooling**
Run:
```bash
./gradlew tasks --quiet
```
Expected: Task list printed without configuration errors.

**Step 4: Commit**
Run:
```bash
git add .
git commit -m "feat: scaffold Android project and configure dependencies"
```

---

### Task 2: Calorie & Stride Calculation Domain Engine (TDD)

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/domain/StepCalculationEngine.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/domain/StepCalculationEngineTest.kt`

**Step 1: Write the failing tests**
`app/src/test/java/com/nordic/stepcounter/domain/StepCalculationEngineTest.kt`:
```kotlin
package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCalculationEngineTest {

    @Test
    fun testDefaultStrideLengthForMale() {
        val heightCm = 180f
        val strideMeters = StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale = true)
        // 180 * 0.415 / 100 = 0.747m
        assertEquals(0.747f, strideMeters, 0.001f)
    }

    @Test
    fun testDefaultStrideLengthForFemale() {
        val heightCm = 165f
        val strideMeters = StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale = false)
        // 165 * 0.413 / 100 = 0.68145m
        assertEquals(0.681f, strideMeters, 0.001f)
    }

    @Test
    fun testDistanceCalculationInKm() {
        val steps = 10000
        val strideMeters = 0.75f
        val distanceKm = StepCalculationEngine.calculateDistanceKm(steps, strideMeters)
        // 10000 * 0.75 / 1000 = 7.5 km
        assertEquals(7.5f, distanceKm, 0.01f)
    }

    @Test
    fun testCaloriesCalculation() {
        val steps = 8000
        val weightKg = 70f
        val calories = StepCalculationEngine.calculateCaloriesKcal(steps, weightKg)
        // 8000 * 70 * 0.00075 = 420.0 kcal
        assertEquals(420f, calories, 0.1f)
    }

    @Test
    fun testActiveTimeEstimation() {
        val steps = 6000
        val minutes = StepCalculationEngine.calculateActiveMinutes(steps)
        // 6000 / 100 = 60 minutes
        assertEquals(60, minutes)
    }
}
```

**Step 2: Run test to verify it fails**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.domain.StepCalculationEngineTest"
```
Expected: Compilation error or failure (`StepCalculationEngine` unresolved).

**Step 3: Implement `StepCalculationEngine.kt`**
`app/src/main/java/com/nordic/stepcounter/domain/StepCalculationEngine.kt`:
```kotlin
package com.nordic.stepcounter.domain

object StepCalculationEngine {
    private const val MALE_STRIDE_RATIO = 0.415f
    private const val FEMALE_STRIDE_RATIO = 0.413f
    private const val CALORIE_MET_FACTOR = 0.00075f
    private const val ESTIMATED_STEPS_PER_MINUTE = 100

    fun calculateDefaultStrideLengthMeters(heightCm: Float, isMale: Boolean): Float {
        val ratio = if (isMale) MALE_STRIDE_RATIO else FEMALE_STRIDE_RATIO
        return (heightCm * ratio) / 100f
    }

    fun calculateDistanceKm(steps: Int, strideMeters: Float): Float {
        if (steps <= 0 || strideMeters <= 0f) return 0f
        return (steps * strideMeters) / 1000f
    }

    fun calculateCaloriesKcal(steps: Int, weightKg: Float): Float {
        if (steps <= 0 || weightKg <= 0f) return 0f
        return steps * weightKg * CALORIE_MET_FACTOR
    }

    fun calculateActiveMinutes(steps: Int): Int {
        if (steps <= 0) return 0
        return steps / ESTIMATED_STEPS_PER_MINUTE
    }
}
```

**Step 4: Run test to verify it passes**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.domain.StepCalculationEngineTest"
```
Expected: PASS.

**Step 5: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/domain/ app/src/test/java/com/nordic/stepcounter/domain/
git commit -m "feat(domain): add StepCalculationEngine with unit tests"
```

---

### Task 3: Room Database Schema & DAO for Daily/Hourly Tracking (TDD)

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/data/local/DailyStepEntity.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/data/local/HourlyStepEntity.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/data/local/StepDao.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/data/local/StepDatabase.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/data/local/StepDaoTest.kt`

**Step 1: Write the failing tests**
`app/src/test/java/com/nordic/stepcounter/data/local/StepDaoTest.kt`:
```kotlin
package com.nordic.stepcounter.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepDaoTest {

    private lateinit var db: StepDatabase
    private lateinit var dao: StepDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, StepDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.stepDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testInsertAndGetDailySteps() = runBlocking {
        val record = DailyStepEntity(
            date = "2026-11-10",
            steps = 5420,
            distanceMeters = 4050f,
            caloriesKcal = 285f,
            activeDurationSeconds = 3200L,
            goalSteps = 10000
        )
        dao.upsertDaily(record)

        val retrieved = dao.getDailyRecord("2026-11-10").first()
        assertNotNull(retrieved)
        assertEquals(5420, retrieved?.steps)
    }

    @Test
    fun testHourlyStepBucketing() = runBlocking {
        val date = "2026-11-10"
        dao.upsertHourly(HourlyStepEntity(date = date, hour = 9, steps = 850))
        dao.upsertHourly(HourlyStepEntity(date = date, hour = 10, steps = 1200))

        val hourlyList = dao.getHourlyRecordsForDate(date).first()
        assertEquals(2, hourlyList.size)
        assertEquals(850, hourlyList.first { it.hour == 9 }.steps)
        assertEquals(1200, hourlyList.first { it.hour == 10 }.steps)
    }
}
```

**Step 2: Run test to verify it fails**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.data.local.StepDaoTest"
```
Expected: FAIL (types do not exist).

**Step 3: Implement Entities, DAO and Database**
`DailyStepEntity.kt`:
```kotlin
package com.nordic.stepcounter.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_steps")
data class DailyStepEntity(
    @PrimaryKey val date: String, // YYYY-MM-DD
    val steps: Int,
    val distanceMeters: Float,
    val caloriesKcal: Float,
    val activeDurationSeconds: Long,
    val goalSteps: Int
)
```

`HourlyStepEntity.kt`:
```kotlin
package com.nordic.stepcounter.data.local

import androidx.room.Entity

@Entity(
    tableName = "hourly_steps",
    primaryKeys = ["date", "hour"]
)
data class HourlyStepEntity(
    val date: String, // YYYY-MM-DD
    val hour: Int,    // 0..23
    val steps: Int
)
```

`StepDao.kt`:
```kotlin
package com.nordic.stepcounter.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StepDao {
    @Query("SELECT * FROM daily_steps WHERE date = :date LIMIT 1")
    fun getDailyRecord(date: String): Flow<DailyStepEntity?>

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :limit")
    fun getRecentDailyRecords(limit: Int): Flow<List<DailyStepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour ASC")
    fun getHourlyRecordsForDate(date: String): Flow<List<HourlyStepEntity>>

    @Upsert
    suspend fun upsertDaily(entity: DailyStepEntity)

    @Upsert
    suspend fun upsertHourly(entity: HourlyStepEntity)

    @Query("SELECT SUM(steps) FROM daily_steps")
    suspend fun getTotalLifetimeSteps(): Long?
}
```

`StepDatabase.kt`:
```kotlin
package com.nordic.stepcounter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DailyStepEntity::class, HourlyStepEntity::class], version = 1, exportSchema = false)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao

    companion object {
        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun getInstance(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    "nordic_steps.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

**Step 4: Run test to verify it passes**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.data.local.StepDaoTest"
```
Expected: PASS.

**Step 5: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/data/local/ app/src/test/java/com/nordic/stepcounter/data/local/
git commit -m "feat(data): add Room entities, DAO, and StepDatabase with unit tests"
```

---

### Task 4: User Profile & Preferences DataStore

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/data/preferences/ProfilePreferences.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/data/preferences/ProfilePreferencesTest.kt`

**Step 1: Write test for preferences models**
`ProfilePreferencesTest.kt`:
Verify defaults: height = 175cm, weight = 70kg, isMale = true, dailyGoal = 6000, isPaused = false, and verify state persistence.

**Step 2: Implement `ProfilePreferences.kt`**
Provide Flow accessors and suspend updater methods for `heightCm`, `weightKg`, `isMale`, `strideLengthMeters`, `dailyGoal`, and `isPaused`.

**Step 3: Run test to verify it passes**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.data.preferences.ProfilePreferencesTest"
```
Expected: PASS.

**Step 4: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/data/preferences/ app/src/test/java/com/nordic/stepcounter/data/preferences/
git commit -m "feat(preferences): add ProfilePreferences DataStore repository"
```

---

### Task 5: Step Sensor Engine & Background Foreground Service

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/service/StepSensorManager.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/service/StepCounterService.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/service/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Implement `StepSensorManager.kt`**
- Manages `SensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)` (19) and `Sensor.TYPE_STEP_DETECTOR` (18).
- Calculates step deltas while taking into account device reboot baseline offsets.
- Handles pause state (ignoring sensor increments when `isPaused == true`).

**Step 2: Implement `StepCounterService.kt`**
- Runs as a Foreground Service with notification channel `step_counter_channel`.
- Notification shows live step count, progress percentage, and interactive "Pause/Resume" action buttons.
- Declares `android:foregroundServiceType="health"`.
- Persists step updates into `StepDatabase` every batch.

**Step 3: Implement `BootReceiver.kt`**
- Intercepts `Intent.ACTION_BOOT_COMPLETED` and `Intent.ACTION_MY_PACKAGE_REPLACED`.
- Starts `StepCounterService` to resume tracking automatically.

**Step 4: Update `AndroidManifest.xml`**
- Add permissions: `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `RECEIVE_BOOT_COMPLETED`.
- Strictly **NO `android.permission.INTERNET`**.

**Step 5: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/service/ app/src/main/AndroidManifest.xml
git commit -m "feat(service): implement low-power hardware StepCounterService and BootReceiver"
```

---

### Task 6: Repository & StepViewModel State Management

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/data/repository/StepRepository.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/StepViewModel.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/ui/StepViewModelTest.kt`

**Step 1: Write test for `StepViewModel`**
Verify that `StepViewModel` calculates distances and calories dynamically when steps update, and emits correct `StepUiState`.

**Step 2: Implement `StepRepository.kt` & `StepViewModel.kt`**
State includes:
- `todaySteps: Int`
- `dailyGoal: Int`
- `distanceKm: Float`
- `caloriesKcal: Float`
- `activeMinutes: Int`
- `isPaused: Boolean`
- `weeklyRecords: List<DailyStepEntity>`
- `hourlyToday: List<HourlyStepEntity>`

**Step 3: Run test to verify it passes**
Run:
```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.ui.StepViewModelTest"
```
Expected: PASS.

**Step 4: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/data/repository/ app/src/main/java/com/nordic/stepcounter/ui/StepViewModel.kt app/src/test/java/com/nordic/stepcounter/ui/
git commit -m "feat(viewmodel): implement StepRepository and StepViewModel with state tests"
```

---

### Task 7: Nordic / Swiss UI Theme & Modular Components

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Color.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Type.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/RadialProgressGauge.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/MetricStatCard.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/PauseResumeButton.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/SwissBarChart.kt`

**Step 1: Implement Theme and Swiss Typography**
- High contrast monospace/grotesk styling.
- Pure black `#0A0A0A`, slate `#161616`, Electric Mint accent `#00E599`.

**Step 2: Implement `RadialProgressGauge.kt`**
- Smooth canvas arc showing current step progress against goal.
- Display large step numerals with micro uppercase "STEPS" label in center.

**Step 3: Implement `MetricStatCard.kt` & `PauseResumeButton.kt`**
- Modular 3-card layout for Distance, Calories, Active Time.
- Tactile high-contrast pause/resume toggle bar.

**Step 4: Implement `SwissBarChart.kt`**
- Minimalist vertical bar chart with touch selection for historical trends.

**Step 5: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/ui/theme/ app/src/main/java/com/nordic/stepcounter/ui/components/
git commit -m "feat(ui): add Nordic/Swiss theme and custom Compose components"
```

---

### Task 8: App Screens & Navigation

**Files:**
- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/TodayScreen.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/HistoryScreen.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/ProfileScreen.kt`
- Modify: `app/src/main/java/com/nordic/stepcounter/MainActivity.kt`

**Step 1: Implement `TodayScreen.kt`**
- Integrates `RadialProgressGauge`, `MetricStatCard` row, and `PauseResumeButton`.
- Handles permission request rationale for `ACTIVITY_RECOGNITION` and `POST_NOTIFICATIONS`.

**Step 2: Implement `HistoryScreen.kt`**
- Tab selector: Week (7 days) / Month (30 days) / Day (24 hours).
- Renders `SwissBarChart` with daily average and personal best.

**Step 3: Implement `ProfileScreen.kt`**
- Height, weight, gender, step length calibration, and daily goal picker.

**Step 4: Assemble `MainActivity.kt`**
- Root screen with clean bottom navigation bar connecting Today, History, and Profile.
- Binds to `StepCounterService`.

**Step 5: Commit**
Run:
```bash
git add app/src/main/java/com/nordic/stepcounter/ui/screens/ app/src/main/java/com/nordic/stepcounter/MainActivity.kt
git commit -m "feat(screens): implement TodayScreen, HistoryScreen, ProfileScreen, and navigation"
```

---

### Task 9: Verification, End-to-End Build & APK Packaging

**Files:**
- Touch: Build output artifacts

**Step 1: Run complete test suite**
Run:
```bash
./gradlew testDebugUnitTest --info
```
Expected: All unit tests pass with 0 failures.

**Step 2: Assemble Release/Debug APK**
Run:
```bash
./gradlew assembleDebug
```
Expected: `app/build/outputs/apk/debug/app-debug.apk` generated successfully.

**Step 3: Audit APK using REA & AAPT**
Run:
```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
/home/kdrai/Android/Sdk/build-tools/36.1.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -i "permission"
```
Expected:
- APK size ~3-5 MB (down from 44.5 MB).
- Zero `android.permission.INTERNET`.
- Zero Ad/Tracking SDKs.

**Step 4: Final commit**
Run:
```bash
git add .
git commit -m "chore: complete clean-room Nordic Step Counter build and verification"
```
