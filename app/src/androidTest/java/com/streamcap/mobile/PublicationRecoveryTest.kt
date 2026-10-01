package com.streamcap.mobile

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.storage.PublicationJournal
import com.streamcap.mobile.storage.RecordingStorage
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicationRecoveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun diskFullDuringPublicationRetainsSourceAndResumesSameDestination() {
        val source = File.createTempFile("qa-enospc-", ".ts", context.cacheDir)
        val key = source.absolutePath
        var partial: String? = null
        try {
            val bytes = ByteArray(700_000) { (it % 127).toByte() }; source.writeBytes(bytes)
            try {
                RecordingStorage(context).publish(source, "qa-full.ts", "video/mp2t", publicationKey = key) {
                    throw java.io.IOException("simulated ENOSPC after first written block")
                }
                fail("Write failure must propagate")
            } catch (_: java.io.IOException) { }
            val entry = PublicationJournal(context, key).read()!!
            partial = entry.getString("uri")
            assertFalse(entry.getBoolean("ready"))
            assertArrayEquals(bytes, source.readBytes())
            val result = RecordingStorage(context).publish(source, "qa-retry.ts", "video/mp2t", publicationKey = key)
            assertEquals(partial, result)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(result))!!.use { it.readBytes() })
        } finally {
            partial?.let { RecordingStorage(context).deletePublished(it) }
            RecordingStorage(context).acknowledgePublication(key); source.delete()
        }
    }

    @Test fun completedPublicationSurvivesMissingDatabaseCommitWithoutAnotherFile() {
        val source = File.createTempFile("qa-publication-", ".ts", context.cacheDir)
        val key = source.absolutePath
        var uri: String? = null
        try {
            source.writeBytes(ByteArray(4096) { it.toByte() })
            val first = RecordingStorage(context)
            uri = first.publish(source, "qa-publish.ts", "video/mp2t", publicationKey = key)
            assertTrue(source.exists())
            // Reconstruct storage as after process restart, with no session URI committed.
            val recovered = RecordingStorage(context).publish(source, "different-recovery-name.ts", "video/mp2t", publicationKey = key)
            assertEquals(uri, recovered)
            assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
        } finally {
            uri?.let { RecordingStorage(context).deletePublished(it) }
            RecordingStorage(context).acknowledgePublication(key); source.delete()
        }
    }

    @Test fun insertBeforeUriJournalCommitIsFoundAndPartialWriteIsTruncated() {
        val source = File.createTempFile("qa-partial-", ".ts", context.cacheDir)
        val name = "qa-intent-${UUID.randomUUID()}.ts"
        val key = source.absolutePath
        var uri: Uri? = null
        try {
            source.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            PublicationJournal(context, key).write(JSONObject().put("name", name).put("directory", "")
                .put("folder", "").put("mime", "video/mp2t").put("ready", false))
            uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp2t")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StreamCap")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
            context.contentResolver.openOutputStream(uri)!!.use { it.write(ByteArray(8192)) }
            val recovered = RecordingStorage(context).publish(source, "ignored.ts", "video/mp2t", publicationKey = key)
            assertEquals(uri.toString(), recovered)
            assertArrayEquals(source.readBytes(), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        } finally {
            val journalUri = PublicationJournal(context, key).read()?.optString("uri")?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            listOfNotNull(journalUri, uri).distinct().forEach { context.contentResolver.delete(it, null, null) }
            RecordingStorage(context).acknowledgePublication(key); source.delete()
        }
    }

    @Test fun unavailableDestinationPreservesSourceAndRetryIntent() {
        val source = File.createTempFile("qa-permission-", ".ts", context.cacheDir)
        val key = source.absolutePath
        try {
            val bytes = ByteArray(4096) { it.toByte() }; source.writeBytes(bytes)
            val badTree = "content://com.android.externalstorage.documents/tree/primary%3Aqa-missing-${UUID.randomUUID()}"
            try {
                RecordingStorage(context).publish(source, "qa.ts", "video/mp2t", badTree, publicationKey = key)
                fail("Invalid destination must fail")
            } catch (_: IllegalStateException) { } catch (_: SecurityException) { }
            assertArrayEquals(bytes, source.readBytes())
            assertNotNull(PublicationJournal(context, key).read())
        } finally { RecordingStorage(context).acknowledgePublication(key); source.delete() }
    }
}
