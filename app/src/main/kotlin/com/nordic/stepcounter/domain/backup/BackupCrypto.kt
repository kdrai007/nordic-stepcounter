package com.nordic.stepcounter.domain.backup

import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

sealed interface EnvelopeInspection {
    data class PlainPayload(val snapshot: BackupSnapshot) : EnvelopeInspection
    data object NeedsPassphrase : EnvelopeInspection
    data class UnsupportedVersion(val version: Int) : EnvelopeInspection
    data object InvalidFormat : EnvelopeInspection
}

sealed interface BackupOpenResult {
    data class Success(val snapshot: BackupSnapshot) : BackupOpenResult
    data object WrongPassphraseOrCorrupt : BackupOpenResult
    data class UnsupportedVersion(val version: Int) : BackupOpenResult
    data object InvalidFormat : BackupOpenResult
}

object BackupCrypto {
    const val FORMAT_ID = "nordic-step-backup"
    const val CURRENT_ENVELOPE_VERSION = 1

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    fun seal(snapshot: BackupSnapshot, passphrase: String?): ByteArray {
        val payloadJson = BackupSerializer.toJson(snapshot)
        if (passphrase.isNullOrEmpty()) {
            val envelope = JSONObject().apply {
                put("format", FORMAT_ID)
                put("version", CURRENT_ENVELOPE_VERSION)
                put("encrypted", false)
                put("payload", payloadJson)
            }
            return envelope.toString(2).toByteArray(Charsets.UTF_8)
        }

        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val plaintext = payloadJson.toString().toByteArray(Charsets.UTF_8)
        val passChars = passphrase.toCharArray()
        var keyBytes: ByteArray? = null

        return try {
            keyBytes = deriveKey(passChars, salt, PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val keySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            val encoder = Base64.getEncoder()
            val envelope = JSONObject().apply {
                put("format", FORMAT_ID)
                put("version", CURRENT_ENVELOPE_VERSION)
                put("encrypted", true)
                put("kdf", KDF_ALGORITHM)
                put("iterations", PBKDF2_ITERATIONS)
                put("salt", encoder.encodeToString(salt))
                put("iv", encoder.encodeToString(iv))
                put("ciphertext", encoder.encodeToString(ciphertext))
            }
            envelope.toString(2).toByteArray(Charsets.UTF_8)
        } finally {
            Arrays.fill(passChars, '\u0000')
            keyBytes?.let { Arrays.fill(it, 0.toByte()) }
            Arrays.fill(plaintext, 0.toByte())
        }
    }

    /**
     * Validates the outer envelope (`format` and `version`) before indicating whether
     * a passphrase is required, so non-backup or newer-version files fail fast.
     */
    fun inspectEnvelope(bytes: ByteArray): EnvelopeInspection {
        if (bytes.isEmpty()) return EnvelopeInspection.InvalidFormat
        val envelope = parseEnvelopeJson(bytes) ?: return EnvelopeInspection.InvalidFormat

        val format = envelope.optString("format", "")
        if (format != FORMAT_ID) return EnvelopeInspection.InvalidFormat

        val version = envelope.optInt("version", -1)
        if (version < 1) return EnvelopeInspection.InvalidFormat
        if (version > CURRENT_ENVELOPE_VERSION) {
            return EnvelopeInspection.UnsupportedVersion(version)
        }

        if (!envelope.has("encrypted")) return EnvelopeInspection.InvalidFormat
        val encrypted = envelope.optBoolean("encrypted", false)
        if (encrypted) {
            val hasCipherFields = envelope.optString("salt", "").isNotEmpty() &&
                envelope.optString("iv", "").isNotEmpty() &&
                envelope.optString("ciphertext", "").isNotEmpty()
            return if (hasCipherFields) {
                EnvelopeInspection.NeedsPassphrase
            } else {
                EnvelopeInspection.InvalidFormat
            }
        }

        val payload = envelope.optJSONObject("payload") ?: return EnvelopeInspection.InvalidFormat
        return try {
            EnvelopeInspection.PlainPayload(BackupSerializer.fromJson(payload))
        } catch (e: UnsupportedBackupVersionException) {
            EnvelopeInspection.UnsupportedVersion(e.version)
        } catch (_: JSONException) {
            EnvelopeInspection.InvalidFormat
        }
    }

    fun openEncrypted(bytes: ByteArray, passphrase: String): BackupOpenResult {
        if (passphrase.isEmpty()) return BackupOpenResult.WrongPassphraseOrCorrupt

        when (val inspection = inspectEnvelope(bytes)) {
            is EnvelopeInspection.PlainPayload -> return BackupOpenResult.Success(inspection.snapshot)
            is EnvelopeInspection.UnsupportedVersion -> return BackupOpenResult.UnsupportedVersion(inspection.version)
            EnvelopeInspection.InvalidFormat -> return BackupOpenResult.InvalidFormat
            EnvelopeInspection.NeedsPassphrase -> Unit
        }

        val envelope = parseEnvelopeJson(bytes) ?: return BackupOpenResult.InvalidFormat
        val kdf = envelope.optString("kdf", KDF_ALGORITHM)
        if (kdf != KDF_ALGORITHM) return BackupOpenResult.InvalidFormat

        val iterations = envelope.optInt("iterations", PBKDF2_ITERATIONS)
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return BackupOpenResult.InvalidFormat

        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(envelope.getString("salt")) }.getOrNull()
            ?: return BackupOpenResult.InvalidFormat
        val iv = runCatching { decoder.decode(envelope.getString("iv")) }.getOrNull()
            ?: return BackupOpenResult.InvalidFormat
        val ciphertext = runCatching { decoder.decode(envelope.getString("ciphertext")) }.getOrNull()
            ?: return BackupOpenResult.InvalidFormat

        if (salt.size < 8 || iv.size != IV_BYTES || ciphertext.isEmpty()) {
            return BackupOpenResult.InvalidFormat
        }

        val passChars = passphrase.toCharArray()
        var keyBytes: ByteArray? = null
        var plaintext: ByteArray? = null

        return try {
            keyBytes = deriveKey(passChars, salt, iterations)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
            val keySpec = SecretKeySpec(keyBytes, KEY_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            plaintext = cipher.doFinal(ciphertext)

            val payloadJson = JSONObject(String(plaintext, Charsets.UTF_8))
            BackupOpenResult.Success(BackupSerializer.fromJson(payloadJson))
        } catch (e: UnsupportedBackupVersionException) {
            BackupOpenResult.UnsupportedVersion(e.version)
        } catch (_: GeneralSecurityException) {
            BackupOpenResult.WrongPassphraseOrCorrupt
        } catch (_: JSONException) {
            BackupOpenResult.InvalidFormat
        } finally {
            Arrays.fill(passChars, '\u0000')
            keyBytes?.let { Arrays.fill(it, 0.toByte()) }
            plaintext?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun parseEnvelopeJson(bytes: ByteArray): JSONObject? {
        return runCatching {
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
