package com.streamcap.mobile

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.storage.RecordingStorage
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RecordingShareTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = RecordingStorage(context)

    @Test fun mediaStoreShareIncludesRealFilenameMimeAndUriGrant() {
        val name = "recording-${UUID.randomUUID()}.ts"
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2ts")
            }))
        try {
            val intent = storage.shareIntent(uri)
            assertEquals(name, intent.getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(name, intent.clipData!!.description.label)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertEquals("video/mp2ts", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun legacyFileShareUsesReadableContentUriAndPreservesName() {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "StreamCap").apply { mkdirs() }
        val file = File(directory, "share-${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(3, 4, 5)) }
        try {
            val intent = storage.shareIntent(Uri.fromFile(file))
            val shared = intent.clipData!!.getItemAt(0).uri
            assertEquals("content", shared.scheme)
            assertEquals(file.name, storage.displayName(shared))
            assertArrayEquals(file.readBytes(), context.contentResolver.openInputStream(shared)!!.use { it.readBytes() })
        } finally { file.delete() }
    }
}
