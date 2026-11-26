package com.nordic.stepcounter.domain.backup

import com.nordic.stepcounter.data.preferences.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private fun sampleSnapshot(): BackupSnapshot = BackupSnapshot(
        schemaVersion = 1,
        exportedAtEpochMs = 1_760_000_000_000L,
        appVersionName = "1.0.0",
        profile = BackupProfile(
            heightCm = 181.5f,
            weightKg = 76.2f,
            isMale = true,
            customStrideLengthMeters = 0.78f,
            dailyGoal = 10_000,
            appTheme = AppTheme.CATPPUCCIN_DARK
        ),
        dailySteps = listOf(
            BackupDailyStep("2026-11-24", 8_420, 6567.6f, 300.5f, 5052L, 10_000),
            BackupDailyStep("2026-11-25", 11_200, 8736.0f, 399.4f, 6720L, 10_000)
        ),
        hourlySteps = listOf(
            BackupHourlyStep("2026-11-24", 9, 3_200),
            BackupHourlyStep("2026-11-25", 18, 5_100)
        )
    )

    @Test
    fun plainRoundTrip_preservesSnapshotAndInspectsAsPlainPayload() {
        val snapshot = sampleSnapshot()
        val bytes = BackupCrypto.seal(snapshot, passphrase = null)

        val inspection = BackupCrypto.inspectEnvelope(bytes)
        assertTrue(inspection is EnvelopeInspection.PlainPayload)
        val decoded = (inspection as EnvelopeInspection.PlainPayload).snapshot
        assertEquals(snapshot.exportedAtEpochMs, decoded.exportedAtEpochMs)
        assertEquals(snapshot.profile, decoded.profile)
        assertEquals(snapshot.dailySteps, decoded.dailySteps)
        assertEquals(snapshot.hourlySteps, decoded.hourlySteps)
    }

    @Test
    fun encryptedRoundTrip_requiresPassphraseAndDecryptsWithCorrectPassphrase() {
        val snapshot = sampleSnapshot()
        val passphrase = "nordic-correct-pass-⌘"
        val bytes = BackupCrypto.seal(snapshot, passphrase = passphrase)

        val inspection = BackupCrypto.inspectEnvelope(bytes)
        assertEquals(EnvelopeInspection.NeedsPassphrase, inspection)

        val opened = BackupCrypto.openEncrypted(bytes, passphrase)
        assertTrue(opened is BackupOpenResult.Success)
        val decoded = (opened as BackupOpenResult.Success).snapshot
        assertEquals(snapshot.profile, decoded.profile)
        assertEquals(snapshot.dailySteps, decoded.dailySteps)
        assertEquals(snapshot.hourlySteps, decoded.hourlySteps)
    }

    @Test
    fun openEncrypted_withWrongPassphraseOrTamperedCiphertext_failsCleanly() {
        val bytes = BackupCrypto.seal(sampleSnapshot(), passphrase = "secret-password")

        val wrongPass = BackupCrypto.openEncrypted(bytes, "wrong-password")
        assertEquals(BackupOpenResult.WrongPassphraseOrCorrupt, wrongPass)

        val text = String(bytes, Charsets.UTF_8)
        val tampered = text.replace("\"ciphertext\": \"", "\"ciphertext\": \"AA").toByteArray(Charsets.UTF_8)
        val tamperedResult = BackupCrypto.openEncrypted(tampered, "secret-password")
        assertTrue(
            tamperedResult is BackupOpenResult.WrongPassphraseOrCorrupt ||
                tamperedResult is BackupOpenResult.InvalidFormat
        )
    }

    @Test
    fun inspectEnvelope_validatesFormatAndVersionBeforePromptingForPassphrase() {
        val randomJson = """{"hello":"world","encrypted":true}""".toByteArray(Charsets.UTF_8)
        assertEquals(EnvelopeInspection.InvalidFormat, BackupCrypto.inspectEnvelope(randomJson))

        val futureEncrypted = """
            {
              "format": "nordic-step-backup",
              "version": 99,
              "encrypted": true,
              "salt": "abc",
              "iv": "def",
              "ciphertext": "ghi"
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        assertEquals(
            EnvelopeInspection.UnsupportedVersion(99),
            BackupCrypto.inspectEnvelope(futureEncrypted)
        )

        assertEquals(EnvelopeInspection.InvalidFormat, BackupCrypto.inspectEnvelope(ByteArray(0)))
        assertEquals(
            EnvelopeInspection.InvalidFormat,
            BackupCrypto.inspectEnvelope("not-json".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun summarize_countsDaysDateRangeAndUsesMaxOfDailyOrHourlySum() {
        val snapshot = BackupSnapshot(
            exportedAtEpochMs = 1_760_000_000_000L,
            appVersionName = "1.0.0",
            profile = BackupProfile(175f, 70f, true, 0f, 6000, AppTheme.NORDIC_DARK),
            dailySteps = listOf(
                BackupDailyStep("2026-11-24", 2_000, 1450f, 60f, 1200L, 6000)
            ),
            hourlySteps = listOf(
                BackupHourlyStep("2026-11-24", 9, 1_200),
                BackupHourlyStep("2026-11-24", 17, 1_500),
                BackupHourlyStep("2026-11-25", 10, 500)
            )
        )

        val summary = BackupSerializer.summarize(snapshot, isEncrypted = true)
        assertTrue(summary.isEncrypted)
        assertEquals(2, summary.dayCount)
        assertEquals("2026-11-24", summary.earliestDate)
        assertEquals("2026-11-25", summary.latestDate)
        assertFalse(summary.totalSteps < 3_200L)
        assertEquals(3_200L, summary.totalSteps)
    }
}
