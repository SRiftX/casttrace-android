package com.streamcap.mobile

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.ui.player.RecordingPlaybackSourceResolver
import com.streamcap.mobile.storage.RecordingStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingPreviewTailTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun mediaFile(directory: File, name: String): File = File(directory, name).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open("preview.ts").use { input ->
            outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun includesCurrentTailAfterPublishedCheckpointWithoutDuplicatingCheckpoint() = runBlocking {
        val directory = File(context.cacheDir, "qa-tail-${UUID.randomUUID()}").apply { mkdir() }
        val resolver = RecordingPlaybackSourceResolver(context)
        try {
            val published = mediaFile(directory, "saved.ts")
            val first = mediaFile(directory, "record.ts.part")
            val tail = mediaFile(directory, "record_002.ts.part")
            val next = mediaFile(directory, "record_003.ts.part")
            val original = tail.readBytes()
            val uri = Uri.fromFile(published).toString()
            val sources = resolver.resolve(uri, listOf(uri), first.path, "TS", true)
            assertEquals(3, sources.size)
            assertEquals(Uri.fromFile(published), sources.first())
            assertArrayEquals(original, tail.readBytes())
            sources.drop(1).forEach { snapshot ->
                assertTrue(snapshot.path!!.endsWith(".mp4"))
                val extractor = android.media.MediaExtractor()
                try {
                    extractor.setDataSource(context, snapshot, null)
                    assertTrue(extractor.trackCount >= 1)
                    extractor.selectTrack(0)
                    extractor.seekTo(1_000_000, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    assertTrue(extractor.sampleTime >= 0)
                } finally { extractor.release() }
            }
            val frozen = File(sources[1].path!!).readBytes()
            tail.appendBytes(original)
            assertArrayEquals(frozen, File(sources[1].path!!).readBytes())
            tail.delete()
            next.delete()
            assertEquals(listOf(Uri.fromFile(published)),
                resolver.resolve(uri, listOf(uri), first.path, "TS", true))
            resolver.close()
            sources.drop(1).forEach { assertFalse(File(it.path!!).exists()) }
        } finally { resolver.close(); directory.deleteRecursively() }
    }

    @Test fun checkpointChangingDuringPreviewReloadsInsteadOfDuplicatingTail() = runBlocking {
        val directory = File(context.cacheDir, "qa-race-${UUID.randomUUID()}").apply { mkdir() }
        val resolver = RecordingPlaybackSourceResolver(context)
        try {
            val first = mediaFile(directory, "record.ts.part")
            mediaFile(directory, "record_002.ts.part")
            val saved = Uri.fromFile(mediaFile(directory, "saved.ts")).toString()
            val saved2 = Uri.fromFile(mediaFile(directory, "saved2.ts")).toString()
            var reads = 0
            val result = resolver.resolve(saved, listOf(saved), first.path, "TS", true) {
                if (reads++ == 0) listOf(saved) else listOf(saved, saved2)
            }
            assertEquals(listOf(Uri.parse(saved), Uri.parse(saved2)), result)
            assertTrue(reads >= 4)
        } finally { resolver.close(); directory.deleteRecursively() }
    }

    @Test fun exportRetryRequiresIdenticalBytesIncludingLength() = runBlocking {
        val directory = File(context.cacheDir, "qa-copy-${UUID.randomUUID()}").apply { mkdir() }
        try {
            val source = File(directory, "source").apply { writeBytes(ByteArray(131077) { (it % 251).toByte() }) }
            val target = File(directory, "target").apply { writeBytes(source.readBytes()) }
            val storage = RecordingStorage(context)
            assertTrue(storage.sameContents(Uri.fromFile(source), Uri.fromFile(target)))
            target.appendBytes(byteArrayOf(1))
            assertFalse(storage.sameContents(Uri.fromFile(source), Uri.fromFile(target)))
            target.writeBytes(ByteArray(131077))
            assertFalse(storage.sameContents(Uri.fromFile(source), Uri.fromFile(target)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun checkpointPublishRetainsRecoverySourceUntilCallerCommits() {
        val directory = File(context.cacheDir, "qa-publish-${UUID.randomUUID()}").apply { mkdir() }
        val storage = RecordingStorage(context)
        var uri: String? = null
        try {
            val source = mediaFile(directory, "record.ts.part")
            val before = source.readBytes()
            uri = storage.publish(source, "qa-checkpoint-${UUID.randomUUID()}.ts", "video/mp2t", retainTemporary = true)
            assertArrayEquals(before, source.readBytes())
            assertArrayEquals(before, context.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() })
            // Simulate failure to commit the session row: recovery input still exists.
            assertTrue(source.length() > 0)
        } finally { uri?.let { storage.deletePublished(it) }; directory.deleteRecursively() }
    }
}
