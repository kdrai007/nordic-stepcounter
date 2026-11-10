# Nordic Step Counter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a high-performance, battery-efficient, and tracker-free Android Step Counter app (`com.nordic.stepcounter`) featuring hardware step detection, Room persistence, and a Nordic/Swiss minimalist Jetpack Compose UI with 8 curated color themes (Nordic, Gruvbox, Catppuccin, and TokyoNight in Dark & Light modes).

**Architecture:** Clean Architecture with Unidirectional Data Flow (UDF). A low-power Android `ForegroundService` with `FOREGROUND_SERVICE_TYPE_HEALTH` binds to hardware `Sensor.TYPE_STEP_COUNTER` (sensor 19) using hardware batching. A pure Kotlin `StepDeltaProcessor` resolves cumulative sensor readings across reboots, pauses, and midnight rollovers. Data is persisted locally in Room SQLite (daily/hourly buckets) and DataStore Preferences. `StepViewModel` exposes reactive `StateFlow<StepUiState>` to a typography-first Jetpack Compose UI without requiring `bindService()`. Zero `INTERNET` permission ensures absolute privacy.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Android Jetpack (Room, DataStore Preferences, Lifecycle ViewModel), Kotlin Coroutines & Flow, Android Hardware Sensor Hub API, JUnit4, MockK, Robolectric, AndroidX Test Core KTX.

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
Add Room runtime, Room KTX, Room Compiler (KSP), DataStore Preferences, and consistent testing libraries (`androidx.test:core-ktx`, `mockk`, `robolectric`):

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
    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    // Room Database (Stable)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("io.mockk:mockk:1.13.13")
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

### Task 2: Calorie, Stride & Sensor Delta Domain Engine (TDD)

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/domain/StepCalculationEngine.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/domain/StepDeltaProcessor.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/domain/StepCalculationEngineTest.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/domain/StepDeltaProcessorTest.kt`

**Step 1: Write the failing tests for physical calculations and sensor delta math**
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
        assertEquals(0.747f, strideMeters, 0.001f)
    }

    @Test
    fun testDefaultStrideLengthForFemale() {
        val heightCm = 165f
        val strideMeters = StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale = false)
        assertEquals(0.681f, strideMeters, 0.001f)
    }

    @Test
    fun testDistanceCalculationInKm() {
        val distanceKm = StepCalculationEngine.calculateDistanceKm(steps = 10000, strideMeters = 0.75f)
        assertEquals(7.5f, distanceKm, 0.01f)
    }

    @Test
    fun testCaloriesCalculation() {
        val calories = StepCalculationEngine.calculateCaloriesKcal(steps = 8000, weightKg = 70f)
        assertEquals(420f, calories, 0.1f)
    }

    @Test
    fun testActiveTimeEstimation() {
        val minutes = StepCalculationEngine.calculateActiveMinutes(steps = 6000)
        assertEquals(60, minutes)
    }
}
```

`app/src/test/java/com/nordic/stepcounter/domain/StepDeltaProcessorTest.kt`:

```kotlin
package com.nordic.stepcounter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepDeltaProcessorTest {

    @Test
    fun `first sensor reading initializes baseline with zero delta`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45000L,
            lastSensorSteps = -1L,
            isPaused = false
        )
        assertEquals(0, result.stepsToAdd)
        assertEquals(45000L, result.newBaselineSensorSteps)
    }

    @Test
    fun `normal sensor increment returns positive delta and updates baseline`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 45120L,
            lastSensorSteps = 45000L,
            isPaused = false
        )
        assertEquals(120, result.stepsToAdd)
        assertEquals(45120L, result.newBaselineSensorSteps)
    }

    @Test
    fun `device reboot where current is less than last resets baseline and counts current`() {
        val result = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 35L,
            lastSensorSteps = 45120L,
            isPaused = false
        )
        assertEquals(35, result.stepsToAdd)
        assertEquals(35L, result.newBaselineSensorSteps)
    }

    @Test
    fun `when paused stepsToAdd is zero but baseline advances so paused steps are never counted on resume`() {
        // User pauses at 1000, walks 500 steps while in transit
        val pausedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1500L,
            lastSensorSteps = 1000L,
            isPaused = true
        )
        assertEquals(0, pausedEvent.stepsToAdd)
        assertEquals(1500L, pausedEvent.newBaselineSensorSteps)

        // User unpauses and takes 25 real steps
        val resumedEvent = StepDeltaProcessor.computeDelta(
            currentSensorSteps = 1525L,
            lastSensorSteps = pausedEvent.newBaselineSensorSteps,
            isPaused = false
        )
        assertEquals(25, resumedEvent.stepsToAdd)
        assertEquals(1525L, resumedEvent.newBaselineSensorSteps)
    }
}
```

**Step 2: Run tests to verify they fail**
Run:

```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.domain.*"
```

Expected: Compilation error (`StepCalculationEngine` and `StepDeltaProcessor` unresolved).

**Step 3: Implement `StepCalculationEngine.kt` and `StepDeltaProcessor.kt`**
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

`app/src/main/java/com/nordic/stepcounter/domain/StepDeltaProcessor.kt`:

```kotlin
package com.nordic.stepcounter.domain

data class StepDeltaResult(
    val stepsToAdd: Int,
    val newBaselineSensorSteps: Long
)

object StepDeltaProcessor {
    /**
     * Resolves cumulative Sensor.TYPE_STEP_COUNTER values into valid step increments,
     * handling first initialization (-1L), device reboots, and pause states.
     */
    fun computeDelta(
        currentSensorSteps: Long,
        lastSensorSteps: Long,
        isPaused: Boolean
    ): StepDeltaResult {
        if (currentSensorSteps < 0L) {
            return StepDeltaResult(0, lastSensorSteps)
        }

        // First sensor reading ever recorded
        if (lastSensorSteps < 0L) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps
            )
        }

        // While paused, advance baseline so steps taken during pause are discarded
        if (isPaused) {
            return StepDeltaResult(
                stepsToAdd = 0,
                newBaselineSensorSteps = currentSensorSteps
            )
        }

        // Device rebooted since last reading (hardware counter reset to 0)
        val rawDelta = if (currentSensorSteps < lastSensorSteps) {
            currentSensorSteps
        } else {
            currentSensorSteps - lastSensorSteps
        }

        return StepDeltaResult(
            stepsToAdd = rawDelta.coerceAtLeast(0L).toInt(),
            newBaselineSensorSteps = currentSensorSteps
        )
    }
}
```

**Step 4: Run tests to verify they pass**
Run:

```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.domain.*"
```

Expected: PASS.

**Step 5: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/domain/ app/src/test/java/com/nordic/stepcounter/domain/
git commit -m "feat(domain): add StepCalculationEngine and StepDeltaProcessor with unit tests"
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

import android.content.Context
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
        val context = ApplicationProvider.getApplicationContext<Context>()
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

    @Query("SELECT * FROM daily_steps WHERE date = :date LIMIT 1")
    suspend fun getDailyRecordOnce(date: String): DailyStepEntity?

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :limit")
    fun getRecentDailyRecords(limit: Int): Flow<List<DailyStepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date ORDER BY hour ASC")
    fun getHourlyRecordsForDate(date: String): Flow<List<HourlyStepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE date = :date AND hour = :hour LIMIT 1")
    suspend fun getHourlyRecordOnce(date: String, hour: Int): HourlyStepEntity?

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

### Task 4: User Profile, Sensor Baseline & Multi-Theme DataStore (TDD)

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/AppTheme.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/data/preferences/ProfilePreferences.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/data/preferences/ProfilePreferencesTest.kt`

**Step 1: Define `AppTheme` enum supporting all 8 themes**
`app/src/main/java/com/nordic/stepcounter/ui/theme/AppTheme.kt`:

```kotlin
package com.nordic.stepcounter.ui.theme

enum class AppTheme(val displayName: String, val isDark: Boolean) {
    NORDIC_DARK("Nordic Dark", true),
    NORDIC_LIGHT("Nordic Light", false),
    GRUVBOX_DARK("Gruvbox Dark", true),
    GRUVBOX_LIGHT("Gruvbox Light", false),
    CATPPUCCIN_DARK("Catppuccin Mocha", true),
    CATPPUCCIN_LIGHT("Catppuccin Latte", false),
    TOKYONIGHT_DARK("TokyoNight Dark", true),
    TOKYONIGHT_LIGHT("TokyoNight Day", false);

    companion object {
        fun fromName(name: String?): AppTheme {
            return entries.firstOrNull { it.name == name } ?: NORDIC_DARK
        }
    }
}
```

**Step 2: Write unit tests for `ProfilePreferences`**
`app/src/test/java/com/nordic/stepcounter/data/preferences/ProfilePreferencesTest.kt`:

```kotlin
package com.nordic.stepcounter.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.nordic.stepcounter.ui.theme.AppTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfilePreferencesTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())
    private lateinit var preferences: ProfilePreferences

    @Before
    fun setup() {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tmpFolder.newFile("test_prefs.preferences_pb") }
        )
        preferences = ProfilePreferences(dataStore)
    }

    @Test
    fun `default values are initialized properly`() = testScope.runTest {
        val state = preferences.userProfileFlow.first()
        assertEquals(175f, state.heightCm, 0.01f)
        assertEquals(70f, state.weightKg, 0.01f)
        assertEquals(true, state.isMale)
        assertEquals(6000, state.dailyGoal)
        assertEquals(false, state.isPaused)
        assertEquals(-1L, state.lastSensorStepValue)
        assertEquals(AppTheme.NORDIC_DARK, state.appTheme)
    }

    @Test
    fun `updating theme and sensor baseline persists accurately`() = testScope.runTest {
        preferences.updateAppTheme(AppTheme.GRUVBOX_DARK)
        preferences.updateLastSensorStepValue(98765L)

        val state = preferences.userProfileFlow.first()
        assertEquals(AppTheme.GRUVBOX_DARK, state.appTheme)
        assertEquals(98765L, state.lastSensorStepValue)
    }
}
```

**Step 3: Implement `ProfilePreferences.kt`**
`app/src/main/java/com/nordic/stepcounter/data/preferences/ProfilePreferences.kt`:

```kotlin
package com.nordic.stepcounter.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_profile_prefs")

data class UserProfileState(
    val heightCm: Float,
    val weightKg: Float,
    val isMale: Boolean,
    val customStrideLengthMeters: Float,
    val dailyGoal: Int,
    val isPaused: Boolean,
    val lastSensorStepValue: Long,
    val appTheme: AppTheme
) {
    val effectiveStrideMeters: Float
        get() = if (customStrideLengthMeters > 0f) {
            customStrideLengthMeters
        } else {
            StepCalculationEngine.calculateDefaultStrideLengthMeters(heightCm, isMale)
        }
}

class ProfilePreferences(private val dataStore: DataStore<Preferences>) {

    companion object {
        val KEY_HEIGHT_CM = floatPreferencesKey("height_cm")
        val KEY_WEIGHT_KG = floatPreferencesKey("weight_kg")
        val KEY_IS_MALE = booleanPreferencesKey("is_male")
        val KEY_CUSTOM_STRIDE_M = floatPreferencesKey("custom_stride_m")
        val KEY_DAILY_GOAL = intPreferencesKey("daily_goal")
        val KEY_IS_PAUSED = booleanPreferencesKey("is_paused")
        val KEY_LAST_SENSOR_STEPS = longPreferencesKey("last_sensor_steps")
        val KEY_APP_THEME = stringPreferencesKey("app_theme")

        @Volatile
        private var INSTANCE: ProfilePreferences? = null

        fun getInstance(context: Context): ProfilePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfilePreferences(context.applicationContext.dataStore).also {
                    INSTANCE = it
                }
            }
        }
    }

    val userProfileFlow: Flow<UserProfileState> = dataStore.data.map { prefs ->
        UserProfileState(
            heightCm = prefs[KEY_HEIGHT_CM] ?: 175f,
            weightKg = prefs[KEY_WEIGHT_KG] ?: 70f,
            isMale = prefs[KEY_IS_MALE] ?: true,
            customStrideLengthMeters = prefs[KEY_CUSTOM_STRIDE_M] ?: 0f,
            dailyGoal = prefs[KEY_DAILY_GOAL] ?: 6000,
            isPaused = prefs[KEY_IS_PAUSED] ?: false,
            lastSensorStepValue = prefs[KEY_LAST_SENSOR_STEPS] ?: -1L,
            appTheme = AppTheme.fromName(prefs[KEY_APP_THEME])
        )
    }

    suspend fun updateProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideMeters: Float = 0f
    ) {
        dataStore.edit { prefs ->
            prefs[KEY_HEIGHT_CM] = heightCm
            prefs[KEY_WEIGHT_KG] = weightKg
            prefs[KEY_IS_MALE] = isMale
            prefs[KEY_CUSTOM_STRIDE_M] = customStrideMeters
        }
    }

    suspend fun updateDailyGoal(goal: Int) {
        dataStore.edit { it[KEY_DAILY_GOAL] = goal.coerceIn(1000, 50000) }
    }

    suspend fun setPaused(paused: Boolean) {
        dataStore.edit { it[KEY_IS_PAUSED] = paused }
    }

    suspend fun updateLastSensorStepValue(sensorValue: Long) {
        dataStore.edit { it[KEY_LAST_SENSOR_STEPS] = sensorValue }
    }

    suspend fun updateAppTheme(theme: AppTheme) {
        dataStore.edit { it[KEY_APP_THEME] = theme.name }
    }
}
```

**Step 4: Run test to verify it passes**
Run:

```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.data.preferences.ProfilePreferencesTest"
```

Expected: PASS.

**Step 5: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/ui/theme/AppTheme.kt app/src/main/java/com/nordic/stepcounter/data/preferences/ app/src/test/java/com/nordic/stepcounter/data/preferences/
git commit -m "feat(preferences): add ProfilePreferences with sensor baseline and 8-theme support"
```

---

### Task 5: Repository, Step Sensor Engine & Android 14/15 Health Foreground Service

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/data/repository/StepRepository.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/service/StepSensorManager.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/service/StepCounterService.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/service/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Step 1: Implement `StepRepository.kt`**
Handles sensor step ingestion using `StepDeltaProcessor`, midnight rollover by querying `LocalDate.now()`, and dynamic goal updates:

```kotlin
package com.nordic.stepcounter.data.repository

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.local.StepDao
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.preferences.UserProfileState
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.domain.StepDeltaProcessor
import com.nordic.stepcounter.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

class StepRepository(
    private val stepDao: StepDao,
    private val preferences: ProfilePreferences
) {
    val userProfileFlow: Flow<UserProfileState> = preferences.userProfileFlow

    fun getDailyRecordFlow(date: String): Flow<DailyStepEntity?> =
        stepDao.getDailyRecord(date)

    fun getRecentDailyRecordsFlow(limit: Int): Flow<List<DailyStepEntity>> =
        stepDao.getRecentDailyRecords(limit)

    fun getHourlyRecordsFlow(date: String): Flow<List<HourlyStepEntity>> =
        stepDao.getHourlyRecordsForDate(date)

    suspend fun processHardwareSensorReading(
        rawSensorSteps: Long,
        date: String = LocalDate.now().toString(),
        hour: Int = LocalTime.now().hour
    ): DailyStepEntity {
        val profile = preferences.userProfileFlow.first()
        val deltaResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = rawSensorSteps,
            lastSensorSteps = profile.lastSensorStepValue,
            isPaused = profile.isPaused
        )

        preferences.updateLastSensorStepValue(deltaResult.newBaselineSensorSteps)

        val existingDaily = stepDao.getDailyRecordOnce(date)
        val newTotalSteps = (existingDaily?.steps ?: 0) + deltaResult.stepsToAdd
        val strideMeters = profile.effectiveStrideMeters

        val updatedDaily = DailyStepEntity(
            date = date,
            steps = newTotalSteps,
            distanceMeters = StepCalculationEngine.calculateDistanceKm(newTotalSteps, strideMeters) * 1000f,
            caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(newTotalSteps, profile.weightKg),
            activeDurationSeconds = StepCalculationEngine.calculateActiveMinutes(newTotalSteps) * 60L,
            goalSteps = profile.dailyGoal
        )
        stepDao.upsertDaily(updatedDaily)

        if (deltaResult.stepsToAdd > 0) {
            val existingHourly = stepDao.getHourlyRecordOnce(date, hour)
            val newHourlySteps = (existingHourly?.steps ?: 0) + deltaResult.stepsToAdd
            stepDao.upsertHourly(
                HourlyStepEntity(date = date, hour = hour, steps = newHourlySteps)
            )
        }

        return updatedDaily
    }

    suspend fun setPaused(paused: Boolean) = preferences.setPaused(paused)

    suspend fun updateDailyGoal(goal: Int) {
        preferences.updateDailyGoal(goal)
        val today = LocalDate.now().toString()
        val existing = stepDao.getDailyRecordOnce(today)
        if (existing != null) {
            stepDao.upsertDaily(existing.copy(goalSteps = goal))
        }
    }

    suspend fun updateProfile(heightCm: Float, weightKg: Float, isMale: Boolean, customStrideMeters: Float) {
        preferences.updateProfile(heightCm, weightKg, isMale, customStrideMeters)
    }

    suspend fun updateAppTheme(theme: AppTheme) {
        preferences.updateAppTheme(theme)
    }
}
```

**Step 2: Implement `StepSensorManager.kt`**
Uses `Sensor.TYPE_STEP_COUNTER` (19) exclusively with a 60-second hardware batching latency (`60_000_000` microseconds) for minimal battery drain:

```kotlin
package com.nordic.stepcounter.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class StepSensorManager(
    context: Context,
    private val onSensorReading: (Long) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepCounterSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    fun startListening() {
        stepCounterSensor?.let { sensor ->
            // 60 seconds hardware batching in low-power DSP
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                60_000_000
            )
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val totalStepsSinceBoot = event.values[0].toLong()
            onSensorReading(totalStepsSinceBoot)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
```

**Step 3: Implement `StepCounterService.kt` with Android 14/15 `FOREGROUND_SERVICE_TYPE_HEALTH` guards**

```kotlin
package com.nordic.stepcounter.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nordic.stepcounter.MainActivity
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.repository.StepRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

class StepCounterService : Service() {

    companion object {
        const val CHANNEL_ID = "step_counter_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_PAUSE = "com.nordic.stepcounter.ACTION_TOGGLE_PAUSE"

        fun hasActivityRecognitionPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: StepRepository
    private lateinit var sensorManager: StepSensorManager

    override fun onCreate() {
        super.onCreate()
        val db = StepDatabase.getInstance(this)
        val prefs = ProfilePreferences.getInstance(this)
        repository = StepRepository(db.stepDao(), prefs)

        createNotificationChannel()
        sensorManager = StepSensorManager(this) { rawSteps ->
            serviceScope.launch {
                val updated = repository.processHardwareSensorReading(rawSteps)
                val profile = repository.userProfileFlow.first()
                updateNotification(updated.steps, updated.goalSteps, profile.isPaused)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14/15 safety guard: stop immediately if ACTIVITY_RECOGNITION was revoked
        if (!hasActivityRecognitionPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_TOGGLE_PAUSE) {
            serviceScope.launch {
                val currentProfile = repository.userProfileFlow.first()
                val newPaused = !currentProfile.isPaused
                repository.setPaused(newPaused)
                val todayRecord = repository.getDailyRecordFlow(LocalDate.now().toString()).first()
                updateNotification(
                    steps = todayRecord?.steps ?: 0,
                    goal = currentProfile.dailyGoal,
                    isPaused = newPaused
                )
            }
        }

        val initialNotification = buildNotification(steps = 0, goal = 6000, isPaused = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        sensorManager.startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        sensorManager.stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Step Tracking Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live daily step count and goal progress"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(steps: Int, goal: Int, isPaused: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(steps, goal, isPaused))
    }

    private fun buildNotification(steps: Int, goal: Int, isPaused: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StepCounterService::class.java).apply { action = ACTION_TOGGLE_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val percent = if (goal > 0) ((steps * 100) / goal).coerceAtMost(100) else 0
        val statusText = if (isPaused) "Paused • $percent% of goal" else "$percent% of $goal goal"
        val actionLabel = if (isPaused) "Resume" else "Pause"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("$steps STEPS")
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, actionLabel, togglePauseIntent)
            .build()
    }
}
```

**Step 4: Implement `BootReceiver.kt` with Android 15 `BOOT_COMPLETED` permission check**

```kotlin
package com.nordic.stepcounter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (StepCounterService.hasActivityRecognitionPermission(context)) {
                val serviceIntent = Intent(context, StepCounterService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
```

**Step 5: Update `AndroidManifest.xml`**
Declare hardware step counter feature, required health FGS permissions, and strictly **omit** `android.permission.INTERNET`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature
        android:name="android.hardware.sensor.stepcounter"
        android:required="true" />

    <uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="true"
        android:label="Nordic Step Counter"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.StepCounterService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="health" />

        <receiver
            android:name=".service.BootReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

**Step 6: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/data/repository/ app/src/main/java/com/nordic/stepcounter/service/ app/src/main/AndroidManifest.xml
git commit -m "feat(service): implement StepRepository, hardware StepSensorManager, and Android 15 health FGS"
```

---

### Task 6: StepViewModel State Management (TDD)

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/ui/StepViewModel.kt`
- Create: `app/src/test/java/com/nordic/stepcounter/ui/StepViewModelTest.kt`

**Step 1: Write test for `StepViewModel`**
`app/src/test/java/com/nordic/stepcounter/ui/StepViewModelTest.kt`:

```kotlin
package com.nordic.stepcounter.ui

import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.preferences.UserProfileState
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.ui.theme.AppTheme
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState combines profile, daily steps, and theme accurately`() = runTest {
        every { repository.userProfileFlow } returns flowOf(
            UserProfileState(
                heightCm = 180f,
                weightKg = 75f,
                isMale = true,
                customStrideLengthMeters = 0.75f,
                dailyGoal = 8000,
                isPaused = false,
                lastSensorStepValue = 1000L,
                appTheme = AppTheme.CATPPUCCIN_DARK
            )
        )
        every { repository.getDailyRecordFlow(any()) } returns flowOf(
            DailyStepEntity(
                date = "2026-11-10",
                steps = 4000,
                distanceMeters = 3000f,
                caloriesKcal = 225f,
                activeDurationSeconds = 2400L,
                goalSteps = 8000
            )
        )
        every { repository.getRecentDailyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())

        val viewModel = StepViewModel(repository, currentDateProvider = { "2026-11-10" })
        val state = viewModel.uiState.first { it.todaySteps == 4000 }

        assertEquals(4000, state.todaySteps)
        assertEquals(8000, state.dailyGoal)
        assertEquals(3.0f, state.distanceKm, 0.01f)
        assertEquals(225f, state.caloriesKcal, 0.1f)
        assertEquals(40, state.activeMinutes)
        assertEquals(AppTheme.CATPPUCCIN_DARK, state.appTheme)
    }
}
```

**Step 2: Implement `StepViewModel.kt`**
`app/src/main/java/com/nordic/stepcounter/ui/StepViewModel.kt`:

```kotlin
package com.nordic.stepcounter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.ui.theme.AppTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StepUiState(
    val todaySteps: Int = 0,
    val dailyGoal: Int = 6000,
    val distanceKm: Float = 0f,
    val caloriesKcal: Float = 0f,
    val activeMinutes: Int = 0,
    val isPaused: Boolean = false,
    val heightCm: Float = 175f,
    val weightKg: Float = 70f,
    val isMale: Boolean = true,
    val customStrideMeters: Float = 0f,
    val effectiveStrideMeters: Float = 0.72f,
    val appTheme: AppTheme = AppTheme.NORDIC_DARK,
    val recentRecords: List<DailyStepEntity> = emptyList(),
    val hourlyToday: List<HourlyStepEntity> = emptyList()
)

class StepViewModel(
    private val repository: StepRepository,
    currentDateProvider: () -> String = { LocalDate.now().toString() }
) : ViewModel() {

    private val todayDate = currentDateProvider()

    val uiState: StateFlow<StepUiState> = combine(
        repository.userProfileFlow,
        repository.getDailyRecordFlow(todayDate),
        repository.getRecentDailyRecordsFlow(30),
        repository.getHourlyRecordsFlow(todayDate)
    ) { profile, todayRecord, recentList, hourlyList ->
        val steps = todayRecord?.steps ?: 0
        val stride = profile.effectiveStrideMeters
        StepUiState(
            todaySteps = steps,
            dailyGoal = profile.dailyGoal,
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
            hourlyToday = hourlyList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StepUiState()
    )

    fun togglePause() {
        viewModelScope.launch {
            repository.setPaused(!uiState.value.isPaused)
        }
    }

    fun updateGoal(newGoal: Int) {
        viewModelScope.launch {
            repository.updateDailyGoal(newGoal)
        }
    }

    fun updateProfile(heightCm: Float, weightKg: Float, isMale: Boolean, customStrideMeters: Float) {
        viewModelScope.launch {
            repository.updateProfile(heightCm, weightKg, isMale, customStrideMeters)
        }
    }

    fun selectTheme(theme: AppTheme) {
        viewModelScope.launch {
            repository.updateAppTheme(theme)
        }
    }

    class Factory(private val repository: StepRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StepViewModel(repository) as T
        }
    }
}
```

**Step 3: Run test to verify it passes**
Run:

```bash
./gradlew testDebugUnitTest --tests "com.nordic.stepcounter.ui.StepViewModelTest"
```

Expected: PASS.

**Step 4: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/ui/StepViewModel.kt app/src/test/java/com/nordic/stepcounter/ui/StepViewModelTest.kt
git commit -m "feat(viewmodel): implement StepViewModel with reactive UDF state and unit tests"
```

---

### Task 7: Multi-Theme System (Nordic, Gruvbox, Catppuccin, TokyoNight) & Swiss UI Components

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Color.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Type.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/RadialProgressGauge.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/MetricStatCard.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/PauseResumeButton.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/components/SwissBarChart.kt`

**Step 1: Implement `Color.kt` with semantic palette tokens for all 8 themes**
`app/src/main/java/com/nordic/stepcounter/ui/theme/Color.kt`:

```kotlin
package com.nordic.stepcounter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class StepColorPalette(
    val background: Color,
    val surface: Color,
    val border: Color,
    val primaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val trackBackground: Color
)

val NordicDarkPalette = StepColorPalette(
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF161616),
    border = Color(0xFF262626),
    primaryText = Color(0xFFFFFFFF),
    mutedText = Color(0xFF8A8A8E),
    accent = Color(0xFF00E599),
    trackBackground = Color(0xFF222222)
)

val NordicLightPalette = StepColorPalette(
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE2E8F0),
    primaryText = Color(0xFF111111),
    mutedText = Color(0xFF64748B),
    accent = Color(0xFF00B377),
    trackBackground = Color(0xFFE2E8F0)
)

val GruvboxDarkPalette = StepColorPalette(
    background = Color(0xFF1D2021),
    surface = Color(0xFF282828),
    border = Color(0xFF3C3836),
    primaryText = Color(0xFFEBDBB2),
    mutedText = Color(0xFFA89984),
    accent = Color(0xFFB8BB26),
    trackBackground = Color(0xFF3C3836)
)

val GruvboxLightPalette = StepColorPalette(
    background = Color(0xFFFBF1C7),
    surface = Color(0xFFEBDBB2),
    border = Color(0xFFD5C4A1),
    primaryText = Color(0xFF3C3836),
    mutedText = Color(0xFF7C6F64),
    accent = Color(0xFF79740E),
    trackBackground = Color(0xFFD5C4A1)
)

val CatppuccinDarkPalette = StepColorPalette( // Mocha
    background = Color(0xFF1E1E2E),
    surface = Color(0xFF313244),
    border = Color(0xFF45475A),
    primaryText = Color(0xFFCDD6F4),
    mutedText = Color(0xFFA6ADC8),
    accent = Color(0xFFA6E3A1),
    trackBackground = Color(0xFF45475A)
)

val CatppuccinLightPalette = StepColorPalette( // Latte
    background = Color(0xFFEFF1F5),
    surface = Color(0xFFE6E9EF),
    border = Color(0xFFCCD0DA),
    primaryText = Color(0xFF4C4F69),
    mutedText = Color(0xFF6C6F85),
    accent = Color(0xFF40A02B),
    trackBackground = Color(0xFFCCD0DA)
)

val TokyoNightDarkPalette = StepColorPalette( // Night
    background = Color(0xFF1A1B26),
    surface = Color(0xFF24283B),
    border = Color(0xFF414868),
    primaryText = Color(0xFFC0CAF5),
    mutedText = Color(0xFF565F89),
    accent = Color(0xFF7AA2F7),
    trackBackground = Color(0xFF292E42)
)

val TokyoNightLightPalette = StepColorPalette( // Day
    background = Color(0xFFE1E2E7),
    surface = Color(0xFFD5D6DB),
    border = Color(0xFFB4B5B9),
    primaryText = Color(0xFF3760BF),
    mutedText = Color(0xFF6172B0),
    accent = Color(0xFF2E7DE9),
    trackBackground = Color(0xFFC4C5CA)
)

fun AppTheme.toStepColorPalette(): StepColorPalette = when (this) {
    AppTheme.NORDIC_DARK -> NordicDarkPalette
    AppTheme.NORDIC_LIGHT -> NordicLightPalette
    AppTheme.GRUVBOX_DARK -> GruvboxDarkPalette
    AppTheme.GRUVBOX_LIGHT -> GruvboxLightPalette
    AppTheme.CATPPUCCIN_DARK -> CatppuccinDarkPalette
    AppTheme.CATPPUCCIN_LIGHT -> CatppuccinLightPalette
    AppTheme.TOKYONIGHT_DARK -> TokyoNightDarkPalette
    AppTheme.TOKYONIGHT_LIGHT -> TokyoNightLightPalette
}

val LocalStepPalette = staticCompositionLocalOf { NordicDarkPalette }
```

**Step 2: Implement `Type.kt` and `Theme.kt`**
`app/src/main/java/com/nordic/stepcounter/ui/theme/Type.kt`:

```kotlin
package com.nordic.stepcounter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val SwissTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        letterSpacing = (-1.5).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 2.sp
    )
)
```

`app/src/main/java/com/nordic/stepcounter/ui/theme/Theme.kt`:

```kotlin
package com.nordic.stepcounter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun NordicStepTheme(
    appTheme: AppTheme = AppTheme.NORDIC_DARK,
    content: @Composable () -> Unit
) {
    val palette = appTheme.toStepColorPalette()

    val colorScheme = if (appTheme.isDark) {
        darkColorScheme(
            background = palette.background,
            surface = palette.surface,
            primary = palette.accent,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            outline = palette.border
        )
    } else {
        lightColorScheme(
            background = palette.background,
            surface = palette.surface,
            primary = palette.accent,
            onBackground = palette.primaryText,
            onSurface = palette.primaryText,
            outline = palette.border
        )
    }

    CompositionLocalProvider(LocalStepPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SwissTypography,
            content = content
        )
    }
}
```

**Step 3: Implement `RadialProgressGauge.kt`, `MetricStatCard.kt`, `PauseResumeButton.kt`, and `SwissBarChart.kt`**
`RadialProgressGauge.kt`:

```kotlin
package com.nordic.stepcounter.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun RadialProgressGauge(
    steps: Int,
    goal: Int,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val targetProgress = if (goal > 0) (steps.toFloat() / goal.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 600),
        label = "stepProgress"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(260.dp)) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val strokeWidth = 14.dp.toPx()
            drawArc(
                color = palette.trackBackground,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = if (isPaused) palette.mutedText else palette.accent,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "%,d".format(steps),
                style = MaterialTheme.typography.displayLarge,
                color = palette.primaryText
            )
            Text(
                text = if (isPaused) "PAUSED" else "STEPS / %,d".format(goal),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText
            )
        }
    }
}
```

`MetricStatCard.kt`:

```kotlin
package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun MetricStatCard(
    value: String,
    unitLabel: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = palette.surface,
        border = BorderStroke(1.dp, palette.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = palette.primaryText
            )
            Text(
                text = unitLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.mutedText
            )
        }
    }
}
```

`PauseResumeButton.kt`:

```kotlin
package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun PauseResumeButton(
    isPaused: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    OutlinedButton(
        onClick = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (isPaused) palette.accent else palette.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = if (isPaused) palette.accent else palette.primaryText
        )
    ) {
        Text(
            text = if (isPaused) "RESUME TRACKING" else "PAUSE TRACKING",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
```

`SwissBarChart.kt`:

```kotlin
package com.nordic.stepcounter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun SwissBarChart(
    values: List<Int>,
    maxValue: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    val safeMax = maxValue.coerceAtLeast(1)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        if (values.isEmpty()) return@Canvas
        val barCount = values.size
        val spacing = 8.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(4f)

        values.forEachIndexed { index, value ->
            val ratio = (value.toFloat() / safeMax.toFloat()).coerceIn(0.04f, 1f)
            val barHeight = size.height * ratio
            val x = index * (barWidth + spacing)
            val y = size.height - barHeight

            drawRoundRect(
                color = if (value > 0) palette.accent else palette.trackBackground,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
}
```

**Step 4: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/ui/theme/ app/src/main/java/com/nordic/stepcounter/ui/components/
git commit -m "feat(ui): add 8-theme color engine and Swiss/Nordic Compose components"
```

---

### Task 8: App Screens, Theme Switcher & UDF Navigation

**Files:**

- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/TodayScreen.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/HistoryScreen.kt`
- Create: `app/src/main/java/com/nordic/stepcounter/ui/screens/ProfileScreen.kt`
- Modify: `app/src/main/java/com/nordic/stepcounter/MainActivity.kt`

**Step 1: Implement `TodayScreen.kt`**

```kotlin
package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.PauseResumeButton
import com.nordic.stepcounter.ui.components.RadialProgressGauge
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun TodayScreen(
    state: StepUiState,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "NORDIC STEP COUNTER",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )

        RadialProgressGauge(
            steps = state.todaySteps,
            goal = state.dailyGoal,
            isPaused = state.isPaused
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricStatCard(
                    value = "%.2f".format(state.distanceKm),
                    unitLabel = "KM",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    value = "%.0f".format(state.caloriesKcal),
                    unitLabel = "KCAL",
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    value = "${state.activeMinutes}",
                    unitLabel = "MINS",
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            PauseResumeButton(
                isPaused = state.isPaused,
                onToggle = onTogglePause
            )
        }
    }
}
```

**Step 2: Implement `HistoryScreen.kt`**

```kotlin
package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.components.MetricStatCard
import com.nordic.stepcounter.ui.components.SwissBarChart
import com.nordic.stepcounter.ui.theme.LocalStepPalette

@Composable
fun HistoryScreen(
    state: StepUiState,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Day (24h), 1 = Week (7d), 2 = Month (30d)

    val chartValues = when (selectedTab) {
        0 -> (0..23).map { hour -> state.hourlyToday.firstOrNull { it.hour == hour }?.steps ?: 0 }
        1 -> state.recentRecords.take(7).reversed().map { it.steps }
        else -> state.recentRecords.take(30).reversed().map { it.steps }
    }

    val bestDay = state.recentRecords.maxOfOrNull { it.steps } ?: state.todaySteps
    val avgSteps = if (state.recentRecords.isNotEmpty()) {
        state.recentRecords.map { it.steps }.average().toInt()
    } else {
        state.todaySteps
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            text = "ANALYTICS & HISTORY",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("24 HOURS", "7 DAYS", "30 DAYS").forEachIndexed { index, title ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    label = { Text(title, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        SwissBarChart(
            values = chartValues,
            maxValue = (chartValues.maxOrNull() ?: state.dailyGoal).coerceAtLeast(1000)
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricStatCard(
                value = "%,d".format(avgSteps),
                unitLabel = "DAILY AVG",
                modifier = Modifier.weight(1f)
            )
            MetricStatCard(
                value = "%,d".format(bestDay),
                unitLabel = "PERSONAL BEST",
                modifier = Modifier.weight(1f)
            )
        }
    }
}
```

**Step 3: Implement `ProfileScreen.kt` with 8-Theme Switcher & Goal Presets**

```kotlin
package com.nordic.stepcounter.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nordic.stepcounter.ui.StepUiState
import com.nordic.stepcounter.ui.theme.AppTheme
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.toStepColorPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    state: StepUiState,
    onSelectGoal: (Int) -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalStepPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = "PROFILE & CALIBRATION",
            style = MaterialTheme.typography.labelSmall,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "DAILY STEP GOAL",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6000, 8000, 10000, 12000).forEach { preset ->
                FilterChip(
                    selected = state.dailyGoal == preset,
                    onClick = { onSelectGoal(preset) },
                    label = { Text("%,d".format(preset), style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "BIOMETRICS & STRIDE",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Height: ${state.heightCm.toInt()} cm • Weight: ${state.weightKg.toInt()} kg • Stride: ${"%.2f".format(state.effectiveStrideMeters)} m",
            style = MaterialTheme.typography.titleMedium,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "COLOR THEME (8 PALETTES)",
            style = MaterialTheme.typography.labelSmall,
            color = palette.primaryText
        )
        Spacer(modifier = Modifier.height(12.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = 2
        ) {
            AppTheme.entries.forEach { themeOption ->
                val optionPalette = themeOption.toStepColorPalette()
                val isSelected = state.appTheme == themeOption
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelectTheme(themeOption) },
                    shape = RoundedCornerShape(10.dp),
                    color = optionPalette.surface,
                    border = BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) optionPalette.accent else optionPalette.border
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = themeOption.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = optionPalette.primaryText
                        )
                        Text(
                            text = if (themeOption.isDark) "DARK" else "LIGHT",
                            style = MaterialTheme.typography.labelSmall,
                            color = optionPalette.accent
                        )
                    }
                }
            }
        }
    }
}
```

**Step 4: Assemble `MainActivity.kt` using UDF and runtime permission launcher**

```kotlin
package com.nordic.stepcounter

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.service.StepCounterService
import com.nordic.stepcounter.ui.StepViewModel
import com.nordic.stepcounter.ui.screens.HistoryScreen
import com.nordic.stepcounter.ui.screens.ProfileScreen
import com.nordic.stepcounter.ui.screens.TodayScreen
import com.nordic.stepcounter.ui.theme.LocalStepPalette
import com.nordic.stepcounter.ui.theme.NordicStepTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StepViewModel by viewModels {
        val db = StepDatabase.getInstance(applicationContext)
        val prefs = ProfilePreferences.getInstance(applicationContext)
        StepViewModel.Factory(StepRepository(db.stepDao(), prefs))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsState()
            var currentTab by remember { mutableIntStateOf(0) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                startStepServiceIfPermitted()
            }

            LaunchedEffect(Unit) {
                val permissionsToRequest = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if (permissionsToRequest.isNotEmpty()) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                } else {
                    startStepServiceIfPermitted()
                }
            }

            NordicStepTheme(appTheme = state.appTheme) {
                val palette = LocalStepPalette.current
                Scaffold(
                    containerColor = palette.background,
                    bottomBar = {
                        NavigationBar(containerColor = palette.surface) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Icon(Icons.Default.DirectionsWalk, contentDescription = "Today") },
                                label = { Text("TODAY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(Icons.Default.BarChart, contentDescription = "History") },
                                label = { Text("HISTORY") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                                label = { Text("PROFILE") }
                            )
                        }
                    }
                ) { innerPadding ->
                    when (currentTab) {
                        0 -> TodayScreen(
                            state = state,
                            onTogglePause = viewModel::togglePause,
                            modifier = Modifier.padding(innerPadding)
                        )
                        1 -> HistoryScreen(
                            state = state,
                            modifier = Modifier.padding(innerPadding)
                        )
                        2 -> ProfileScreen(
                            state = state,
                            onSelectGoal = viewModel::updateGoal,
                            onSelectTheme = viewModel::selectTheme,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }

    private fun startStepServiceIfPermitted() {
        if (StepCounterService.hasActivityRecognitionPermission(this)) {
            val intent = Intent(this, StepCounterService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
```

**Step 5: Commit**
Run:

```bash
git add app/src/main/java/com/nordic/stepcounter/ui/screens/ app/src/main/java/com/nordic/stepcounter/MainActivity.kt
git commit -m "feat(screens): implement Today, History, Profile with 8-theme selector and UDF navigation"
```

---

### Task 9: Verification, End-to-End Build & APK Packaging

**Files:**

- Touch: Build output artifacts

**Step 1: Run complete unit test suite**
Run:

```bash
./gradlew testDebugUnitTest --info
```

Expected: All unit tests (`StepCalculationEngineTest`, `StepDeltaProcessorTest`, `StepDaoTest`, `ProfilePreferencesTest`, `StepViewModelTest`) pass with 0 failures.

**Step 2: Assemble Debug & Release APK**
Run:

```bash
./gradlew assembleDebug
```

Expected: `app/build/outputs/apk/debug/app-debug.apk` generated successfully.

**Step 3: Audit APK using AAPT**
Run:

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
/home/kdrai/Android/Sdk/build-tools/36.1.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -i "permission"
```

Expected:

- APK size ~3–5 MB (down from 44.5 MB).
- Zero `android.permission.INTERNET`.
- Zero Ad/Tracking SDKs.

**Step 4: Final commit**
Run:

```bash
git add .
git commit -m "chore: complete clean-room Nordic Step Counter build and verification"
```
