package com.streamcap.mobile

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.danmaku.VideoExportMode
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File
import com.streamcap.mobile.danmaku.DanmakuAssetManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

class RecordingShareExportTest {
    @Test fun sharesOriginalAndBurnedRealChatThroughFileProvider() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("recordingId"))
        val id = requireNotNull(args.getString("recordingId")).toLong()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val session = requireNotNull(app.container.repository.session(id))
        val events = DanmakuAssetManager(app, app.container.repository).events(id)
        assertTrue(events.isNotEmpty() && events.all { !it.parserVersion.contains("simulat") && it.mediaTimeMs != null })
        val source = Uri.parse(session.outputUris.firstOrNull() ?: requireNotNull(session.outputUri))
        val before = digest(app, source)
        val original = app.container.shareExporter.prepare(listOf(id), VideoExportMode.ORIGINAL, DanmakuAppearance())
        assertTrue(original.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertArrayEquals(before, digest(app, requireNotNull(original.clipData).getItemAt(0).uri))
        val burned = withTimeout(360_000) {
            app.container.shareExporter.prepare(listOf(id), VideoExportMode.WITH_DANMAKU, DanmakuAppearance()) {
                println("SHARE_PROGRESS $it")
            }
        }
        assertEquals(Intent.ACTION_SEND, burned.action)
        assertEquals("video/mp4", burned.type)
        val shared = requireNotNull(burned.clipData).getItemAt(0).uri
        assertEquals("com.streamcap.mobile.files", shared.authority)
        app.contentResolver.openInputStream(shared).use { assertTrue(requireNotNull(it).read() >= 0) }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(app, shared, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
        } finally { extractor.release() }
        assertArrayEquals(before, digest(app, source))
        val ids = args.getString("batchIds")?.split(',')?.map(String::toLong)
        if (ids != null) {
            val batch = app.container.shareExporter.prepare(ids, VideoExportMode.ORIGINAL, DanmakuAppearance())
            assertEquals(Intent.ACTION_SEND_MULTIPLE, batch.action)
            assertTrue(requireNotNull(batch.clipData).itemCount >= 2)
            for (index in 0 until requireNotNull(batch.clipData).itemCount) {
                app.contentResolver.openInputStream(requireNotNull(batch.clipData).getItemAt(index).uri).use {
                    assertTrue(requireNotNull(it).read() >= 0)
                }
            }
            val batchBurned = withTimeout(360_000) {
                app.container.shareExporter.prepare(ids, VideoExportMode.WITH_DANMAKU, DanmakuAppearance())
            }
            assertEquals(Intent.ACTION_SEND_MULTIPLE, batchBurned.action)
            assertTrue(requireNotNull(batchBurned.clipData).itemCount >= 2)
            for (index in 0 until requireNotNull(batchBurned.clipData).itemCount) {
                val uri = requireNotNull(batchBurned.clipData).getItemAt(index).uri
                assertEquals("com.streamcap.mobile.files", uri.authority)
                app.contentResolver.openInputStream(uri).use { assertTrue(requireNotNull(it).read() >= 0) }
            }
        }
        val root = File(app.cacheDir, "shared")
        val directories = root.listFiles().orEmpty().map { it.name }.toSet()
        val task = launch {
            app.container.shareExporter.prepare(listOf(id), VideoExportMode.WITH_DANMAKU, DanmakuAppearance()) {
                if (it.contains("正在合成弹幕")) cancel("QA share cancellation")
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(directories, root.listFiles().orEmpty().map { it.name }.toSet())
        assertArrayEquals(before, digest(app, source))
        println("SHARE_EXPORT_OK session=$id originalPreserved=true shared=$shared")
    }

    private fun digest(app: StreamCapApplication, uri: Uri): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }
}
