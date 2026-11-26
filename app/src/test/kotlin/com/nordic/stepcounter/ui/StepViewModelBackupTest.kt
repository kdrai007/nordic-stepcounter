package com.nordic.stepcounter.ui

import android.net.Uri
import com.nordic.stepcounter.data.backup.BackupFileIO
import com.nordic.stepcounter.data.backup.SafReadResult
import com.nordic.stepcounter.data.preferences.AppTheme
import com.nordic.stepcounter.data.preferences.UserProfile
import com.nordic.stepcounter.data.repository.StepRepository
import com.nordic.stepcounter.domain.backup.BackupCrypto
import com.nordic.stepcounter.domain.backup.BackupDailyStep
import com.nordic.stepcounter.domain.backup.BackupProfile
import com.nordic.stepcounter.domain.backup.BackupSnapshot
import com.nordic.stepcounter.domain.backup.RestoreStats
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepViewModelBackupTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: StepRepository
    private lateinit var fakeFileIO: FakeBackupFileIO
    private lateinit var viewModel: StepViewModel

    private class FakeBackupFileIO : BackupFileIO {
        var lastWrittenBytes: ByteArray? = null
        var writeResult: Result<Unit> = Result.success(Unit)
        var nextReadResult: SafReadResult = SafReadResult.ReadError("unset")

        override suspend fun writeBytes(uri: Uri, bytes: ByteArray): Result<Unit> {
            lastWrittenBytes = bytes
            return writeResult
        }

        override suspend fun readBytes(uri: Uri, maxBytes: Long): SafReadResult = nextReadResult
    }

    private fun sampleSnapshot() = BackupSnapshot(
        schemaVersion = 1,
        exportedAtEpochMs = 1_760_000_000_000L,
        appVersionName = "1.0.0",
        profile = BackupProfile.fromUserProfile(UserProfile(dailyGoal = 9000, appTheme = AppTheme.NORDIC_DARK)),
        dailySteps = listOf(BackupDailyStep("2026-11-25", 7500, 5400f, 220f, 4500L, 9000)),
        hourlySteps = emptyList()
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        every { repository.userProfileFlow } returns flowOf(UserProfile())
        every { repository.sensorStateFlow } returns flowOf(null)
        every { repository.getDailyRecordFlow(any()) } returns flowOf(null)
        every { repository.getDailyRecordsInRangeFlow(any(), any()) } returns flowOf(emptyList())
        every { repository.getHourlyRecordsFlow(any()) } returns flowOf(emptyList())
        every { repository.getBestDayStepsFlow(any()) } returns flowOf(null)
        every { repository.getEarliestRecordDateFlow(any()) } returns flowOf(null)
        every { repository.lastBackupTimestampFlow } returns flowOf(0L)

        fakeFileIO = FakeBackupFileIO()
        viewModel = StepViewModel(
            repository = repository,
            backupFileIO = fakeFileIO,
            appVersionName = "1.0.0",
            ioDispatcher = testDispatcher,
            cpuDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exportBackupToUri_writesBytesAndUpdatesTimestamp() = runTest {
        val snapshot = sampleSnapshot()
        coEvery { repository.captureBackupSnapshot("1.0.0") } returns snapshot

        val uri = mockk<Uri>()
        viewModel.exportBackupToUri(uri, passphrase = "abc")

        val state = viewModel.backupState.value
        assertEquals(BackupUiState.Success(BackupSuccessType.EXPORT, dayCount = 1), state)
        assertTrue(fakeFileIO.lastWrittenBytes != null)
        coVerify { repository.markManualBackupCompleted(1_760_000_000_000L) }
    }

    @Test
    fun inspectBackupUri_handlesTooLargeAndInvalidFormat() = runTest {
        val uri = mockk<Uri>()
        fakeFileIO.nextReadResult = SafReadResult.TooLarge(BackupFileIO.MAX_BACKUP_BYTES)
        viewModel.inspectBackupUri(uri)
        assertEquals(BackupUiState.Error(BackupErrorReason.FILE_TOO_LARGE), viewModel.backupState.value)

        fakeFileIO.nextReadResult = SafReadResult.Success("""{"unrelated":true}""".toByteArray())
        viewModel.inspectBackupUri(uri)
        assertEquals(BackupUiState.Error(BackupErrorReason.INVALID_FORMAT), viewModel.backupState.value)
    }

    @Test
    fun inspectAndUnlockEncryptedBackup_transitionsThroughPassphrasePreviewAndConfirm() = runTest {
        val snapshot = sampleSnapshot()
        val sealed = BackupCrypto.seal(snapshot, passphrase = "my-secret")
        fakeFileIO.nextReadResult = SafReadResult.Success(sealed)
        val uri = mockk<Uri>()

        viewModel.inspectBackupUri(uri)
        val promptState = viewModel.backupState.value
        assertTrue(promptState is BackupUiState.PassphraseRequired)

        viewModel.unlockEncryptedBackup((promptState as BackupUiState.PassphraseRequired).pendingBytes, "wrong")
        val retryState = viewModel.backupState.value
        assertTrue(retryState is BackupUiState.PassphraseRequired && retryState.isRetry)

        viewModel.unlockEncryptedBackup(promptState.pendingBytes, "my-secret")
        val previewState = viewModel.backupState.value
        assertTrue(previewState is BackupUiState.RestorePreview)

        coEvery { repository.restoreBackupSnapshot(any(), true) } returns RestoreStats(1, 1, 0, true)
        viewModel.confirmRestore((previewState as BackupUiState.RestorePreview).snapshot, restoreProfile = true)
        assertEquals(
            BackupUiState.Success(BackupSuccessType.RESTORE, dayCount = 1, updatedDays = 1),
            viewModel.backupState.value
        )
    }
}
