package com.nordic.stepcounter.data.backup

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

sealed interface SafReadResult {
    data class Success(val bytes: ByteArray) : SafReadResult
    data class TooLarge(val maxBytes: Long) : SafReadResult
    data class ReadError(val message: String) : SafReadResult
}

interface BackupFileIO {
    suspend fun writeBytes(uri: Uri, bytes: ByteArray): Result<Unit>
    suspend fun readBytes(uri: Uri, maxBytes: Long = MAX_BACKUP_BYTES): SafReadResult

    companion object {
        const val MAX_BACKUP_BYTES: Long = 10L * 1024L * 1024L
    }
}

class SafBackupFileIO(
    private val context: Context
) : BackupFileIO {

    override suspend fun writeBytes(uri: Uri, bytes: ByteArray): Result<Unit> {
        return runCatching {
            val resolver = context.contentResolver
            val outputStream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri)
                ?: throw IOException("Unable to open output stream for backup destination")
            outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
        }
    }

    override suspend fun readBytes(uri: Uri, maxBytes: Long): SafReadResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return SafReadResult.ReadError("Unable to open selected file")

            inputStream.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                val out = ByteArrayOutputStream()
                var totalRead = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    totalRead += read
                    if (totalRead > maxBytes) {
                        return SafReadResult.TooLarge(maxBytes)
                    }
                    out.write(buffer, 0, read)
                }
                SafReadResult.Success(out.toByteArray())
            }
        } catch (e: IOException) {
            SafReadResult.ReadError(e.message ?: "I/O error while reading backup file")
        } catch (e: SecurityException) {
            SafReadResult.ReadError(e.message ?: "Permission denied while reading backup file")
        }
    }
}
