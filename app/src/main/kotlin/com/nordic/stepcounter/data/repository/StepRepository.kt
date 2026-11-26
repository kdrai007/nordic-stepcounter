package com.nordic.stepcounter.data.repository

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.nordic.stepcounter.data.local.DailyStepEntity
import com.nordic.stepcounter.data.local.HourlyStepEntity
import com.nordic.stepcounter.data.local.SensorStateEntity
import com.nordic.stepcounter.data.local.StepDao
import com.nordic.stepcounter.data.local.StepDatabase
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.ProfilePreferences
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.domain.StepDeltaProcessor
import com.nordic.stepcounter.domain.backup.BackupDailyStep
import com.nordic.stepcounter.domain.backup.BackupHourlyStep
import com.nordic.stepcounter.domain.backup.BackupProfile
import com.nordic.stepcounter.domain.backup.BackupSnapshot
import com.nordic.stepcounter.domain.backup.RestoreStats
import com.nordic.stepcounter.domain.backup.StepBackupMerger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class StepRepository(
    private val stepDao: StepDao,
    private val preferences: ProfilePreferences,
    private val bootCountProvider: () -> Int = { -1 },
    private val installationIdProvider: () -> String = { DEFAULT_INSTALLATION_ID },
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeNanosProvider: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
) {
    private val sensorMutex = Mutex()

    val userProfileFlow: Flow<UserProfile> = preferences.userProfileFlow
    val sensorStateFlow: Flow<SensorStateEntity?> = stepDao.getSensorStateFlow()
    val isPausedFlow: Flow<Boolean> = sensorStateFlow
        .map { it?.isPaused ?: false }
        .distinctUntilChanged()
    val dailyGoalFlow: Flow<Int> = preferences.dailyGoalFlow
    val appThemeFlow: Flow<AppTheme> = preferences.appThemeFlow
    val lastBackupTimestampFlow: Flow<Long> = preferences.lastBackupTimestampFlow

    fun getDailyRecordFlow(date: String): Flow<DailyStepEntity?> =
        stepDao.getDailyRecord(date)

    suspend fun getDailyRecordDirect(date: String): DailyStepEntity? =
        stepDao.getDailyRecordDirect(date)

    fun getDailyRecordsInRangeFlow(startDate: String, endDate: String): Flow<List<DailyStepEntity>> =
        stepDao.getDailyRecordsInRange(startDate, endDate)

    fun getBestDayStepsFlow(maxDate: String): Flow<Int?> =
        stepDao.getBestDaySteps(maxDate)

    fun getEarliestRecordDateFlow(maxDate: String): Flow<String?> =
        stepDao.getEarliestRecordDate(maxDate)

    fun getHourlyRecordsFlow(date: String): Flow<List<HourlyStepEntity>> =
        stepDao.getHourlyRecordsForDate(date)

    suspend fun getSensorStateDirect(): SensorStateEntity? = sensorMutex.withLock {
        getOrMigrateSensorStateLocked()
    }

    /**
     * Initializes a record for [date] if one does not already exist and ensures any legacy
     * DataStore sensor state has been migrated into Room.
     */
    suspend fun initializeTodayRecord(
        date: String = LocalDate.now(zoneIdProvider()).toString()
    ): DailyStepEntity = sensorMutex.withLock {
        getOrMigrateSensorStateLocked()
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

    /**
     * Ingests a raw hardware Sensor.TYPE_STEP_COUNTER reading.
     * Attributes steps using the hardware event timestamp, handles BOOT_COUNT reboots,
     * cross-device restore detection, multi-day downtime gaps, pause/resume sync,
     * and atomically persists sensor state + daily + hourly records in one @Transaction.
     */
    suspend fun processHardwareSensorReading(
        rawSensorSteps: Long,
        date: String? = null,
        hour: Int? = null,
        eventTimestampNanos: Long = 0L
    ): DailyStepEntity = sensorMutex.withLock {
        val profile = preferences.userProfileFlow.first()
        val sensorState = getOrMigrateSensorStateLocked()

        val nowMillis = currentTimeMillisProvider()
        val eventTimeMillis = resolveEventTimeMillis(
            eventTimestampNanos = eventTimestampNanos,
            nowMillis = nowMillis,
            elapsedRealtimeNanos = elapsedRealtimeNanosProvider()
        )
        val zonedDateTime = Instant.ofEpochMilli(eventTimeMillis).atZone(zoneIdProvider())
        val resolvedDate = date ?: zonedDateTime.toLocalDate().toString()
        val resolvedHour = (hour ?: zonedDateTime.hour).coerceIn(0, 23)

        if (rawSensorSteps < 0L) {
            return@withLock stepDao.getDailyRecordDirect(resolvedDate) ?: DailyStepEntity(
                date = resolvedDate,
                steps = 0,
                distanceMeters = 0f,
                caloriesKcal = 0f,
                activeDurationSeconds = 0L,
                goalSteps = profile.dailyGoal
            )
        }

        val currentInstallId = installationIdProvider()
        val isCrossDeviceRestore = sensorState.installationId.isNotEmpty() &&
            sensorState.installationId != currentInstallId

        val resolvedLocalDate = runCatching { LocalDate.parse(resolvedDate) }.getOrNull()
        val lastRecordedLocalDate = sensorState.lastRecordedDate
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val daysBetween = if (resolvedLocalDate != null && lastRecordedLocalDate != null) {
            ChronoUnit.DAYS.between(lastRecordedLocalDate, resolvedLocalDate)
        } else {
            0L
        }
        val isMultiDayGap = daysBetween > 1L

        val currentBootCount = bootCountProvider()
        val deltaResult = StepDeltaProcessor.computeDelta(
            currentSensorSteps = rawSensorSteps,
            lastSensorSteps = sensorState.lastSensorStepValue,
            isPaused = sensorState.isPaused,
            currentBootCount = currentBootCount,
            lastBootCount = sensorState.lastBootCount,
            pendingResumeBaseline = sensorState.pendingResumeBaseline,
            forceBaselineReset = isCrossDeviceRestore || isMultiDayGap
        )

        val shouldRecordDowntimeNotice = isMultiDayGap &&
            !isCrossDeviceRestore &&
            !sensorState.isPaused &&
            sensorState.lastSensorStepValue >= 0L &&
            rawSensorSteps != sensorState.lastSensorStepValue

        val nextGapFrom = if (shouldRecordDowntimeNotice) {
            sensorState.lastRecordedDate
        } else {
            sensorState.downtimeGapFromDate
        }
        val nextGapTo = if (shouldRecordDowntimeNotice) {
            resolvedDate
        } else {
            sensorState.downtimeGapToDate
        }

        val updatedSensorState = sensorState.copy(
            lastSensorStepValue = deltaResult.newBaselineSensorSteps,
            lastBootCount = deltaResult.newBootCount,
            lastRecordedDate = resolvedDate,
            lastEventTimestampMs = eventTimeMillis,
            pendingResumeBaseline = deltaResult.newPendingResumeBaseline,
            installationId = currentInstallId,
            downtimeGapFromDate = nextGapFrom,
            downtimeGapToDate = nextGapTo
        )

        val existingDaily = stepDao.getDailyRecordDirect(resolvedDate)
        val baseSteps = existingDaily?.steps ?: 0
        val newTotalSteps = (baseSteps.toLong() + deltaResult.stepsToAdd)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val strideMeters = profile.effectiveStrideLengthMeters
        val distanceMeters = StepCalculationEngine.calculateDistanceKm(newTotalSteps, strideMeters) * 1000f
        val caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(newTotalSteps, profile.weightKg, strideMeters)
        val activeDurationSeconds = StepCalculationEngine.calculateActiveSeconds(newTotalSteps)

        val updatedDaily = DailyStepEntity(
            date = resolvedDate,
            steps = newTotalSteps,
            distanceMeters = distanceMeters,
            caloriesKcal = caloriesKcal,
            activeDurationSeconds = activeDurationSeconds,
            goalSteps = profile.dailyGoal
        )

        val hourlyEntity = if (deltaResult.stepsToAdd > 0) {
            val existingHourly = stepDao.getHourlyRecordDirect(resolvedDate, resolvedHour)
            val newHourlySteps = ((existingHourly?.steps ?: 0).toLong() + deltaResult.stepsToAdd)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            HourlyStepEntity(
                date = resolvedDate,
                hour = resolvedHour,
                steps = newHourlySteps
            )
        } else {
            null
        }

        stepDao.persistSensorUpdate(updatedSensorState, updatedDaily, hourlyEntity)

        return@withLock updatedDaily
    }

    suspend fun setPaused(
        isPaused: Boolean,
        pendingResumeBaseline: Boolean = false
    ) = sensorMutex.withLock {
        val current = getOrMigrateSensorStateLocked()
        val nextPending = if (isPaused) false else pendingResumeBaseline
        stepDao.upsertSensorState(
            current.copy(
                isPaused = isPaused,
                pendingResumeBaseline = nextPending,
                installationId = current.installationId.ifEmpty { installationIdProvider() }
            )
        )
    }

    suspend fun dismissDowntimeNotice() = sensorMutex.withLock {
        val current = getOrMigrateSensorStateLocked()
        if (current.downtimeGapFromDate.isNotEmpty() || current.downtimeGapToDate.isNotEmpty()) {
            stepDao.upsertSensorState(
                current.copy(
                    downtimeGapFromDate = "",
                    downtimeGapToDate = ""
                )
            )
        }
    }

    suspend fun updateDailyGoal(goal: Int) = sensorMutex.withLock {
        val safeGoal = goal.coerceIn(1_000, 100_000)
        preferences.updateDailyGoal(safeGoal)
        val today = LocalDate.now(zoneIdProvider()).toString()
        val affected = stepDao.updateDailyGoal(today, safeGoal)
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
                        goalSteps = safeGoal
                    )
                )
            }
        }
    }

    suspend fun updatePhysicalProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideLengthMeters: Float = 0.0f
    ) = sensorMutex.withLock {
        val currentProfile = preferences.userProfileFlow.first()
        val safeHeight = if (heightCm.isFinite()) heightCm.coerceIn(100f, 250f) else currentProfile.heightCm
        val safeWeight = if (weightKg.isFinite()) weightKg.coerceIn(30f, 250f) else currentProfile.weightKg
        val safeCustomStride = if (customStrideLengthMeters.isFinite() && customStrideLengthMeters > 0f) {
            customStrideLengthMeters.coerceIn(0.30f, 1.50f)
        } else {
            0.0f
        }
        preferences.updatePhysicalProfile(safeHeight, safeWeight, isMale, safeCustomStride)

        val today = LocalDate.now(zoneIdProvider()).toString()
        val todayRecord = stepDao.getDailyRecordDirect(today)
        if (todayRecord != null) {
            val strideMeters = if (safeCustomStride > 0f) {
                safeCustomStride
            } else {
                StepCalculationEngine.calculateDefaultStrideLengthMeters(safeHeight, isMale)
            }
            val distanceMeters = StepCalculationEngine.calculateDistanceKm(todayRecord.steps, strideMeters) * 1000f
            val caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(todayRecord.steps, safeWeight, strideMeters)
            val activeDurationSeconds = StepCalculationEngine.calculateActiveSeconds(todayRecord.steps)
            stepDao.upsertDaily(
                todayRecord.copy(
                    distanceMeters = distanceMeters,
                    caloriesKcal = caloriesKcal,
                    activeDurationSeconds = activeDurationSeconds
                )
            )
        }
    }

    suspend fun updateAppTheme(theme: AppTheme) {
        preferences.updateAppTheme(theme)
    }

    /**
     * Captures a point-in-time snapshot of user profile and daily/hourly step history.
     * Holds [sensorMutex] strictly for the Room + DataStore read; serialization and encryption
     * happen outside the lock.
     */
    suspend fun captureBackupSnapshot(appVersionName: String): BackupSnapshot = sensorMutex.withLock {
        val profile = preferences.userProfileFlow.first()
        val daily = stepDao.getAllDailyRecordsDirect()
        val hourly = stepDao.getAllHourlyRecordsDirect()
        BackupSnapshot(
            exportedAtEpochMs = currentTimeMillisProvider(),
            appVersionName = appVersionName,
            profile = BackupProfile.fromUserProfile(profile),
            dailySteps = daily.map(BackupDailyStep::fromEntity),
            hourlySteps = hourly.map(BackupHourlyStep::fromEntity)
        )
    }

    suspend fun markManualBackupCompleted(timestampMs: Long = currentTimeMillisProvider()) {
        preferences.updateLastBackupTimestamp(timestampMs)
    }

    /**
     * Merges a parsed [BackupSnapshot] into local Room storage without disturbing hardware
     * [SensorStateEntity] baselines. Holds [sensorMutex] strictly for the database read/merge/write.
     */
    suspend fun restoreBackupSnapshot(
        snapshot: BackupSnapshot,
        restoreProfile: Boolean
    ): RestoreStats = sensorMutex.withLock {
        val currentProfile = preferences.userProfileFlow.first()
        val effectiveProfile = if (restoreProfile) {
            snapshot.profile.toUserProfile()
        } else {
            currentProfile
        }
        val localDaily = stepDao.getAllDailyRecordsDirect()
        val localHourly = stepDao.getAllHourlyRecordsDirect()

        val merged = StepBackupMerger.merge(
            localDaily = localDaily,
            localHourly = localHourly,
            snapshot = snapshot,
            effectiveProfile = effectiveProfile
        )

        stepDao.applyMergedBackup(merged.dailyUpserts, merged.hourlyUpserts)
        if (restoreProfile) {
            preferences.restoreProfile(effectiveProfile)
            val today = LocalDate.now(zoneIdProvider()).toString()
            stepDao.updateDailyGoal(today, effectiveProfile.dailyGoal)
        }

        RestoreStats(
            mergedDaysCount = merged.mergedDaysCount,
            updatedDaysCount = merged.updatedDaysCount,
            updatedHourlyCount = merged.updatedHourlyCount,
            profileRestored = restoreProfile
        )
    }

    private suspend fun getOrMigrateSensorStateLocked(): SensorStateEntity {
        val existing = stepDao.getSensorStateDirect()
        if (existing != null) return existing

        val installId = installationIdProvider()
        val legacy = preferences.consumeLegacySensorState()
        val initial = if (legacy != null) {
            SensorStateEntity(
                lastSensorStepValue = legacy.lastSensorStepValue,
                lastBootCount = -1,
                lastRecordedDate = legacy.lastRecordedDate,
                lastEventTimestampMs = 0L,
                isPaused = legacy.isPaused,
                pendingResumeBaseline = false,
                installationId = installId
            )
        } else {
            SensorStateEntity(installationId = installId)
        }
        stepDao.upsertSensorState(initial)
        return initial
    }

    companion object {
        private const val DEFAULT_INSTALLATION_ID = "local"
        private const val INSTALLATION_ID_FILENAME = "nordic_sensor_install_id"
        private const val MAX_FUTURE_TIMESTAMP_SKEW_NANOS = 60_000_000_000L
        private const val MAX_PAST_TIMESTAMP_AGE_NANOS = 30L * 24L * 3600L * 1_000_000_000L

        @Volatile
        private var INSTANCE: StepRepository? = null

        fun resolveEventTimeMillis(
            eventTimestampNanos: Long,
            nowMillis: Long,
            elapsedRealtimeNanos: Long
        ): Long {
            if (eventTimestampNanos <= 0L || elapsedRealtimeNanos <= 0L) {
                return nowMillis
            }
            val deltaNanos = elapsedRealtimeNanos - eventTimestampNanos
            if (deltaNanos < -MAX_FUTURE_TIMESTAMP_SKEW_NANOS || deltaNanos > MAX_PAST_TIMESTAMP_AGE_NANOS) {
                return nowMillis
            }
            return nowMillis - (deltaNanos / 1_000_000L)
        }

        private fun readBootCount(context: Context): Int {
            return runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }.getOrDefault(-1)
        }

        private fun readOrCreateInstallationId(context: Context): String {
            return runCatching {
                val file = File(context.noBackupFilesDir, INSTALLATION_ID_FILENAME)
                if (file.exists()) {
                    val existing = file.readText().trim()
                    if (existing.isNotEmpty()) return@runCatching existing
                }
                val created = UUID.randomUUID().toString()
                file.parentFile?.mkdirs()
                file.writeText(created)
                created
            }.getOrDefault(DEFAULT_INSTALLATION_ID)
        }

        fun getInstance(context: Context): StepRepository {
            val appContext = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(
                    stepDao = StepDatabase.getInstance(appContext).stepDao(),
                    preferences = ProfilePreferences.getInstance(appContext),
                    bootCountProvider = { readBootCount(appContext) },
                    installationIdProvider = { readOrCreateInstallationId(appContext) }
                ).also { INSTANCE = it }
            }
        }

        fun getInstance(stepDao: StepDao, preferences: ProfilePreferences): StepRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepRepository(stepDao, preferences).also { INSTANCE = it }
            }
        }

        internal fun setInstanceForTesting(repository: StepRepository) {
            synchronized(this) {
                INSTANCE = repository
            }
        }

        fun resetInstance() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
