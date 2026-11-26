package com.nordic.stepcounter.data.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SafBackupFileIOTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private lateinit var fileIO: SafBackupFileIO

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fileIO = SafBackupFileIO(context)
    }

    @Test
    fun writeAndReadBytes_roundTripsCleanly() = runTest {
        val file = tempFolder.newFile("backup.json")
        val uri = Uri.fromFile(file)
        val payload = """{"format":"nordic-step-backup","version":1}""".toByteArray(Charsets.UTF_8)

        val writeResult = fileIO.writeBytes(uri, payload)
        assertTrue(writeResult.isSuccess)

        val readResult = fileIO.readBytes(uri)
        assertTrue(readResult is SafReadResult.Success)
        assertArrayEquals(payload, (readResult as SafReadResult.Success).bytes)
    }

    @Test
    fun readBytes_enforcesMaxBytesLimit() = runTest {
        val file = tempFolder.newFile("oversized.bin")
        file.writeBytes(ByteArray(2048) { 0x41 })
        val uri = Uri.fromFile(file)

        val result = fileIO.readBytes(uri, maxBytes = 1024L)
        assertEquals(SafReadResult.TooLarge(1024L), result)
    }

    @Test
    fun readBytes_missingFileReturnsReadError() = runTest {
        val missing = File(tempFolder.root, "does-not-exist.json")
        val result = fileIO.readBytes(Uri.fromFile(missing))
        assertTrue(result is SafReadResult.ReadError)
    }
}
