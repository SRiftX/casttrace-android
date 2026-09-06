package com.streamcap.mobile

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.storage.RecordingStorage
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RecordingRenameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = RecordingStorage(context)

    @Test fun renamesLocalFileWithoutChangingBytesOrExtension() {
        val directory = File(context.cacheDir, "rename-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory, "source.mp4").apply { writeBytes(byteArrayOf(3, 8, 9)) }
        try {
            val result = storage.renamePublished(Uri.fromFile(source), "new name")
            assertEquals("new name.mp4", storage.displayName(result))
            assertFalse(source.exists())
            assertArrayEquals(byteArrayOf(3, 8, 9), File(result.path!!).readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun existingLocalDestinationIsNotOverwritten() {
        val directory = File(context.cacheDir, "rename-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File(directory, "source.mp4").apply { writeText("source") }
        val destination = File(directory, "existing.mp4").apply { writeText("existing") }
        try {
            try {
                storage.renamePublished(Uri.fromFile(source), "existing")
                fail("Collision must fail")
            } catch (_: IllegalStateException) {
                assertEquals("source", source.readText())
                assertEquals("existing", destination.readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun rejectsPathTraversalAndEmptyNames() {
        val source = File.createTempFile("rename-", ".mp4", context.cacheDir).apply { writeText("source") }
        try {
            listOf("../escape", "", "..", "bad\\name", "bad\nname").forEach { name ->
                try {
                    storage.renamePublished(Uri.fromFile(source), name)
                    fail("Invalid name accepted")
                } catch (_: IllegalArgumentException) {
                    assertEquals("source", source.readText())
                }
            }
        } finally {
            source.delete()
        }
    }

    @Test fun mediaStoreRenameKeepsUriAndContent() {
        val target = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "rename-${UUID.randomUUID()}.bin")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }))
        try {
            val bytes = byteArrayOf(5, 6, 7, 8)
            context.contentResolver.openOutputStream(target)!!.use { it.write(bytes) }
            val name = "renamed-${UUID.randomUUID()}"
            val renamed = storage.renamePublished(target, name)
            assertEquals(target, renamed)
            assertEquals("$name.bin", storage.displayName(renamed))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(renamed)!!.use { it.readBytes() })
        } finally {
            context.contentResolver.delete(target, null, null)
        }
    }
}
