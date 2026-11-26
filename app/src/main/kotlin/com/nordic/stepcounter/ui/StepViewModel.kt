package com.nordic.stepcounter.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nordic.stepcounter.data.backup.BackupFileIO
import com.nordic.stepcounter.data.backup.SafBackupFileIO
import com.nordic.stepcounter.data.backup.SafReadResult
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.domain.StepCalculationEngine
import com.nordic.stepcounter.domain.backup.BackupCrypto
import com.nordic.stepcounter.domain.backup.BackupOpenResult
import com.nordic.stepcounter.domain.backup.BackupSerializer
import com.nordic.stepcounter.domain.backup.BackupSnapshot
import com.nordic.stepcounter.domain.backup.BackupSummary
import com.nordic.stepcounter.domain.backup.EnvelopeInspection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class BackupSuccessType {
    EXPORT,
    RESTORE
}

enum class BackupErrorReason {
    READ_FAILED,
    WRITE_FAILED,
    FILE_TOO_LARGE,
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    UNKNOWN
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data class InProgress(val isExport: Boolean) : BackupUiState
    data class PassphraseRequired(
        val pendingBytes: ByteArray,
        val isRetry: Boolean = false
    ) : BackupUiState
    data class RestorePreview(
        val summary: BackupSummary,
        val snapshot: BackupSnapshot
    ) : BackupUiState
    data class Success(
        val type: BackupSuccessType,
        val dayCount: Int,
        val updatedDays: Int = 0
    ) : BackupUiState
    data class Error(val reason: BackupErrorReason) : BackupUiState
}

class StepViewModel private constructor(
    private val repository: StepRepository,
    externalDateFlow: Flow<String>?,
    currentDateProvider: () -> String,
    zoneIdProvider: () -> ZoneId,
    private val backupFileIO: BackupFileIO? = null,
    private val appVersionName: String = "1.0.0",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val dateRefreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _backupState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

    constructor(
        repository: StepRepository,
        currentDateFlow: Flow<String>
    ) : this(
        repository = repository,
        externalDateFlow = currentDateFlow,
        currentDateProvider = { LocalDate.now().toString() },
        zoneIdProvider = { ZoneId.systemDefault() }
    )

    constructor(
        repository: StepRepository,
        currentDateProvider: () -> String = { LocalDate.now().toString() },
        zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }
    ) : this(
        repository = repository,
        externalDateFlow = null,
        currentDateProvider = currentDateProvider,
        zoneIdProvider = zoneIdProvider
    )

    internal constructor(
        repository: StepRepository,
        backupFileIO: BackupFileIO,
        appVersionName: String = "1.0.0",
        currentDateProvider: () -> String = { LocalDate.now().toString() },
        zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
    ) : this(
        repository = repository,
        externalDateFlow = null,
        currentDateProvider = currentDateProvider,
        zoneIdProvider = zoneIdProvider,
        backupFileIO = backupFileIO,
        appVersionName = appVersionName,
        ioDispatcher = ioDispatcher,
        cpuDispatcher = cpuDispatcher
    )

    private val effectiveDateFlow: Flow<String> = externalDateFlow ?: createMidnightAwareDateFlow(
        refreshSignal = dateRefreshSignal,
        currentDateProvider = currentDateProvider,
        zoneIdProvider = zoneIdProvider
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StepUiState> = effectiveDateFlow
        .onEach { todayDate ->
            repository.initializeTodayRecord(todayDate)
        }
        .flatMapLatest { todayDate ->
            val todayLocal = runCatching { LocalDate.parse(todayDate) }.getOrElse { LocalDate.now() }
            val startDate = todayLocal.minusDays(29).toString()

            val analyticsMetaFlow = combine(
                repository.getBestDayStepsFlow(todayDate),
                repository.getEarliestRecordDateFlow(todayDate),
                repository.lastBackupTimestampFlow.onStart { emit(0L) }
            ) { bestDay, earliestDate, lastBackupTs ->
                Triple(bestDay, earliestDate, lastBackupTs)
            }

            combine(
                repository.userProfileFlow,
                repository.sensorStateFlow,
                repository.getDailyRecordFlow(todayDate),
                repository.getDailyRecordsInRangeFlow(startDate, todayDate),
                combine(
                    repository.getHourlyRecordsFlow(todayDate),
                    analyticsMetaFlow,
                    ::Pair
                )
            ) { profile, sensorState, todayRecord, recentList, hourlyAndMeta ->
                val hourlyList = hourlyAndMeta.first
                val (bestDayFromDb, earliestDateFromDb, lastBackupTimestampMs) = hourlyAndMeta.second

                val steps = todayRecord?.steps ?: 0
                val dailyGoal = todayRecord?.goalSteps?.takeIf { it > 0 } ?: profile.dailyGoal
                val stride = profile.effectiveStrideLengthMeters
                val progress = (steps.toFloat() / dailyGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
                val isPaused = sensorState?.isPaused ?: false
                val personalBest = maxOf(bestDayFromDb ?: 0, steps)
                val earliestDate = earliestDateFromDb ?: todayDate

                StepUiState(
                    isLoaded = true,
                    todaySteps = steps,
                    dailyGoal = dailyGoal,
                    goalProgress = progress,
                    distanceKm = StepCalculationEngine.calculateDistanceKm(steps, stride),
                    caloriesKcal = StepCalculationEngine.calculateCaloriesKcal(steps, profile.weightKg, stride),
                    activeMinutes = StepCalculationEngine.calculateActiveMinutes(steps),
                    isPaused = isPaused,
                    heightCm = profile.heightCm,
                    weightKg = profile.weightKg,
                    isMale = profile.isMale,
                    customStrideMeters = profile.customStrideLengthMeters,
                    effectiveStrideLengthMeters = stride,
                    appTheme = profile.appTheme,
                    recentRecords = recentList,
                    hourlyToday = hourlyList,
                    personalBestSteps = personalBest,
                    earliestRecordDate = earliestDate,
                    downtimeGapFromDate = sensorState?.downtimeGapFromDate.orEmpty(),
                    downtimeGapToDate = sensorState?.downtimeGapToDate.orEmpty(),
                    userProfile = profile,
                    currentDate = todayDate,
                    lastBackupTimestampMs = lastBackupTimestampMs
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StepUiState()
        )

    fun refreshCurrentDate() {
        dateRefreshSignal.tryEmit(Unit)
    }

    fun togglePause() {
        viewModelScope.launch {
            val currentPaused = repository.isPausedFlow.first()
            repository.setPaused(!currentPaused)
        }
    }

    fun setPaused(isPaused: Boolean) {
        viewModelScope.launch {
            repository.setPaused(isPaused)
        }
    }

    fun dismissDowntimeNotice() {
        viewModelScope.launch {
            repository.dismissDowntimeNotice()
        }
    }

    fun updateDailyGoal(newGoal: Int) {
        viewModelScope.launch {
            repository.updateDailyGoal(newGoal)
        }
    }

    fun updatePhysicalProfile(
        heightCm: Float,
        weightKg: Float,
        isMale: Boolean,
        customStrideMeters: Float = 0f
    ) {
        viewModelScope.launch {
            repository.updatePhysicalProfile(
                heightCm = heightCm,
                weightKg = weightKg,
                isMale = isMale,
                customStrideLengthMeters = customStrideMeters
            )
        }
    }

    fun updateAppTheme(theme: AppTheme) {
        viewModelScope.launch {
            repository.updateAppTheme(theme)
        }
    }

    fun exportBackupToUri(uri: Uri, passphrase: String?) {
        val fileIO = backupFileIO ?: run {
            _backupState.value = BackupUiState.Error(BackupErrorReason.WRITE_FAILED)
            return
        }
        viewModelScope.launch {
            _backupState.value = BackupUiState.InProgress(isExport = true)
            val snapshot = runCatching {
                repository.captureBackupSnapshot(appVersionName)
            }.getOrElse {
                _backupState.value = BackupUiState.Error(BackupErrorReason.UNKNOWN)
                return@launch
            }

            val bytes = runCatching {
                withContext(cpuDispatcher) {
                    BackupCrypto.seal(snapshot, passphrase)
                }
            }.getOrElse {
                _backupState.value = BackupUiState.Error(BackupErrorReason.UNKNOWN)
                return@launch
            }

            val writeResult = withContext(ioDispatcher) {
                fileIO.writeBytes(uri, bytes)
            }
            if (writeResult.isSuccess) {
                repository.markManualBackupCompleted(snapshot.exportedAtEpochMs)
                _backupState.value = BackupUiState.Success(
                    type = BackupSuccessType.EXPORT,
                    dayCount = snapshot.dailySteps.size
                )
            } else {
                _backupState.value = BackupUiState.Error(BackupErrorReason.WRITE_FAILED)
            }
        }
    }

    fun inspectBackupUri(uri: Uri) {
        val fileIO = backupFileIO ?: run {
            _backupState.value = BackupUiState.Error(BackupErrorReason.READ_FAILED)
            return
        }
        viewModelScope.launch {
            _backupState.value = BackupUiState.InProgress(isExport = false)
            val bytes = when (val readResult = withContext(ioDispatcher) { fileIO.readBytes(uri) }) {
                is SafReadResult.Success -> readResult.bytes
                is SafReadResult.TooLarge -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.FILE_TOO_LARGE)
                    return@launch
                }
                is SafReadResult.ReadError -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.READ_FAILED)
                    return@launch
                }
            }

            when (val inspection = withContext(cpuDispatcher) { BackupCrypto.inspectEnvelope(bytes) }) {
                is EnvelopeInspection.PlainPayload -> {
                    _backupState.value = BackupUiState.RestorePreview(
                        summary = BackupSerializer.summarize(inspection.snapshot, isEncrypted = false),
                        snapshot = inspection.snapshot
                    )
                }
                EnvelopeInspection.NeedsPassphrase -> {
                    _backupState.value = BackupUiState.PassphraseRequired(
                        pendingBytes = bytes,
                        isRetry = false
                    )
                }
                is EnvelopeInspection.UnsupportedVersion -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.UNSUPPORTED_VERSION)
                }
                EnvelopeInspection.InvalidFormat -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.INVALID_FORMAT)
                }
            }
        }
    }

    fun unlockEncryptedBackup(pendingBytes: ByteArray, passphrase: String) {
        viewModelScope.launch {
            when (val result = withContext(cpuDispatcher) { BackupCrypto.openEncrypted(pendingBytes, passphrase) }) {
                is BackupOpenResult.Success -> {
                    _backupState.value = BackupUiState.RestorePreview(
                        summary = BackupSerializer.summarize(result.snapshot, isEncrypted = true),
                        snapshot = result.snapshot
                    )
                }
                BackupOpenResult.WrongPassphraseOrCorrupt -> {
                    _backupState.value = BackupUiState.PassphraseRequired(
                        pendingBytes = pendingBytes,
                        isRetry = true
                    )
                }
                is BackupOpenResult.UnsupportedVersion -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.UNSUPPORTED_VERSION)
                }
                BackupOpenResult.InvalidFormat -> {
                    _backupState.value = BackupUiState.Error(BackupErrorReason.INVALID_FORMAT)
                }
            }
        }
    }

    fun confirmRestore(snapshot: BackupSnapshot, restoreProfile: Boolean) {
        viewModelScope.launch {
            _backupState.value = BackupUiState.InProgress(isExport = false)
            val stats = runCatching {
                repository.restoreBackupSnapshot(snapshot, restoreProfile)
            }.getOrElse {
                _backupState.value = BackupUiState.Error(BackupErrorReason.UNKNOWN)
                return@launch
            }
            _backupState.value = BackupUiState.Success(
                type = BackupSuccessType.RESTORE,
                dayCount = stats.mergedDaysCount,
                updatedDays = stats.updatedDaysCount
            )
        }
    }

    fun dismissBackupState() {
        _backupState.value = BackupUiState.Idle
    }

    class Factory(
        private val repository: StepRepository,
        private val backupFileIO: BackupFileIO? = null,
        private val appVersionName: String = "1.0.0"
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StepViewModel::class.java)) {
                return if (backupFileIO != null) {
                    StepViewModel(
                        repository = repository,
                        backupFileIO = backupFileIO,
                        appVersionName = appVersionName
                    ) as T
                } else {
                    StepViewModel(repository) as T
                }
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    companion object {
        private const val MIN_MIDNIGHT_DELAY_MS = 1_000L
        private const val MAX_MIDNIGHT_DELAY_MS = 24L * 60L * 60L * 1_000L + 60_000L

        private fun createMidnightAwareDateFlow(
            refreshSignal: Flow<Unit>,
            currentDateProvider: () -> String,
            zoneIdProvider: () -> ZoneId
        ): Flow<String> {
            val midnightTicker = flow {
                while (true) {
                    val dateStr = currentDateProvider()
                    emit(dateStr)
                    val parsedDate = runCatching { LocalDate.parse(dateStr) }.getOrElse { LocalDate.now(zoneIdProvider()) }
                    val nextMidnight = parsedDate.plusDays(1).atStartOfDay(zoneIdProvider()).toInstant()
                    val delayMillis = Duration.between(Instant.now(), nextMidnight)
                        .toMillis()
                        .coerceIn(MIN_MIDNIGHT_DELAY_MS, MAX_MIDNIGHT_DELAY_MS)
                    delay(delayMillis + 50L)
                }
            }
            val signalDates = refreshSignal.map { currentDateProvider() }
            return merge(midnightTicker, signalDates).distinctUntilChanged()
        }

        fun provideFactory(
            repository: StepRepository
        ): ViewModelProvider.Factory = Factory(repository)

        fun provideFactory(
            context: Context
        ): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val versionName = runCatching {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0.0"
            }.getOrDefault("1.0.0")
            return Factory(
                repository = StepRepository.getInstance(appContext),
                backupFileIO = SafBackupFileIO(appContext),
                appVersionName = versionName
            )
        }
    }
}
