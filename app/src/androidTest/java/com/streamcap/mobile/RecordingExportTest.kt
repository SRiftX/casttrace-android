package com.streamcap.mobile

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.storage.RecordingStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = RecordingStorage(context)

    private fun destination(): Uri = requireNotNull(context.contentResolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "casttrace-export-test-${UUID.randomUUID()}.bin")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        },
    ))

    @Test fun exportsExactBytesWithoutChangingSource() = runBlocking {
        val source = File.createTempFile("export-source-", ".bin", context.cacheDir)
        val target = destination()
        try {
            val bytes = ByteArray(2 * 1024 * 1024 + 137) { (it % 251).toByte() }
            source.writeBytes(bytes)
            var progress = 0L
            val copied = storage.exportCopy(Uri.fromFile(source), target) {
                assertTrue(it > progress)
                progress = it
            }
            assertEquals(bytes.size.toLong(), copied)
            assertEquals(copied, progress)
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, context.contentResolver.openInputStream(target)!!.use { it.readBytes() })
        } finally {
            source.delete()
            context.contentResolver.delete(target, null, null)
        }
    }

    @Test fun cancellationRemovesPartialCopyAndKeepsSource() = runBlocking {
        val source = File.createTempFile("export-source-", ".bin", context.cacheDir)
        val target = destination()
        try {
            source.writeBytes(ByteArray(1024 * 1024) { 42 })
            try {
                storage.exportCopy(Uri.fromFile(source), target) { throw CancellationException("test") }
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertEquals(1024 * 1024L, source.length())
                context.contentResolver.query(target, arrayOf("_id"), null, null, null)!!.use {
                    assertFalse("Partial destination must be removed", it.moveToFirst())
                }
            }
        } finally {
            source.delete()
            context.contentResolver.delete(target, null, null)
        }
    }

    @Test fun missingSourceRemovesEmptyDestination() = runBlocking {
        val target = destination()
        try {
            try {
                storage.exportCopy(Uri.fromFile(File(context.cacheDir, UUID.randomUUID().toString())), target)
                fail("Missing source must fail")
            } catch (_: java.io.FileNotFoundException) {
                context.contentResolver.query(target, arrayOf("_id"), null, null, null)!!.use {
                    assertFalse(it.moveToFirst())
                }
            }
        } finally {
            context.contentResolver.delete(target, null, null)
        }
    }

    @Test fun refusesToOverwriteSource() = runBlocking {
        val source = File.createTempFile("export-source-", ".bin", context.cacheDir)
        try {
            source.writeText("original")
            try {
                storage.exportCopy(Uri.fromFile(source), Uri.fromFile(source))
                fail("Same destination must be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals("original", source.readText())
            }
        } finally {
            source.delete()
        }
    }
}
