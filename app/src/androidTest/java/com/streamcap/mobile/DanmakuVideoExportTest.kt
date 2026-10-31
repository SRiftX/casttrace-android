package com.streamcap.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.DanmakuAssetManager
import com.streamcap.mobile.danmaku.VideoDanmakuOverlay
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.ui.player.RecordingPlaybackSourceResolver
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

@OptIn(UnstableApi::class)
@dagger.hilt.android.testing.HiltAndroidTest
class DanmakuVideoExportTest : HiltGraphTest() {
    @Test fun exportsSavedRealChatIntoVideo() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an explicit saved recording", args.containsKey("recordingId"))
        val id = requireNotNull(args.getString("recordingId")).toLong()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val session = requireNotNull(app.testGraph.repository.session(id))
        val source = Uri.parse(session.outputUris.firstOrNull() ?: requireNotNull(session.outputUri))
        val events = DanmakuAssetManager(app, app.testGraph.repository).events(id)
        assertTrue("Must use captured server messages", events.isNotEmpty() && events.all {
            it.platform in setOf("douyin", "huya", "douyu", "bilibili") && !it.parserVersion.contains("simulat") && it.mediaTimeMs != null
        })
        val before = digest(app, source)
        val original = File(app.cacheDir, "qa-original-$id.${session.containerFormat.lowercase()}")
        try {
            app.testGraph.recordingStorage.exportCopy(source, Uri.fromFile(original))
            assertArrayEquals(before, digest(app, Uri.fromFile(original)))
        } finally { original.delete() }

        val appearance = DanmakuAppearance(colorRgb = 0xFF00FF, opacity = 1f)
        val target = requireNotNull(app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "CastTrace-real-danmaku-$id-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CastTrace-QA")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        try {
            withTimeout(360_000) {
                app.testGraph.videoExporter.export(id, source.toString(), target, appearance) { println("EXPORT_PROGRESS $it") }
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(app, target, null)
                val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
                assertTrue("Audio must be retained", formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
            } finally { extractor.release() }
            RecordingPlaybackSourceResolver(app).use { resolver ->
                val playable = withContext(Dispatchers.IO) {
                    resolver.resolve(source.toString(), listOf(source.toString()), null, session.containerFormat).single()
                }
                val plain = MediaMetadataRetriever()
                val burned = MediaMetadataRetriever()
                try {
                    plain.setDataSource(app, playable)
                    burned.setDataSource(app, target)
                    val duration = requireNotNull(plain.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    val exportedDuration = requireNotNull(burned.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                    assertTrue("Duration must be retained: $duration / $exportedDuration", kotlin.math.abs(duration - exportedDuration) < 1500)
                    val time = requireNotNull(events.firstOrNull { it.mediaTimeMs!! in 0..(duration - 3500) }?.mediaTimeMs) + 2500
                    val a = requireNotNull(plain.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                    val b = requireNotNull(burned.getFrameAtTime(time * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                    val c = requireNotNull(burned.getFrameAtTime((time + 800) * 1000, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        val plainPixels = magentaPixels(a)
                        val burnedPixels = magentaPixels(b)
                        assertTrue("Burned chat pixels: plain=$plainPixels burned=$burnedPixels", burnedPixels > plainPixels + 100)
                        assertTrue("Chat must still be visible after movement", magentaPixels(c) > 100)
                        val overlay = VideoDanmakuOverlay(events, appearance, 0)
                        overlay.configure(Size(b.width, b.height))
                        try {
                            val first = overlay.getBitmap(time * 1000).copy(Bitmap.Config.ARGB_8888, false)
                            val next = overlay.getBitmap((time + 800) * 1000)
                            try { assertFalse("Overlay must move", first.sameAs(next)) } finally { first.recycle() }
                            val offsetOverlay = VideoDanmakuOverlay(events, appearance, time)
                            offsetOverlay.configure(Size(b.width, b.height))
                            try { assertTrue("Part offset must match global time", next.sameAs(offsetOverlay.getBitmap(800_000))) }
                            finally { offsetOverlay.release() }
                        } finally { overlay.release() }
                        File(app.getExternalFilesDir(null), "export-$id-frame.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { a.recycle(); b.recycle(); c.recycle() }
                    println("REAL_VIDEO_EXPORT_OK session=$id target=$target duration=$exportedDuration sampleMs=$time")
                } finally { plain.release(); burned.release() }
            }
            assertArrayEquals("Original recording must be unchanged", before, digest(app, source))
            app.contentResolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } catch (failure: Throwable) {
            app.contentResolver.delete(target, null, null)
            throw failure
        }

        val cancelled = File(app.cacheDir, "qa-cancelled-$id.mp4").apply { writeBytes(byteArrayOf(0)) }
        val remuxTemps = app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("recording-preview-") }.toSet()
        val task = launch {
            app.testGraph.videoExporter.export(id, source.toString(), Uri.fromFile(cancelled), appearance) {
                if (it.startsWith("正在合成弹幕")) cancel("QA cancel during encoding")
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertFalse("Cancelled target must be removed", cancelled.exists())
        assertFalse(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("danmaku-export-") })
        assertEquals(remuxTemps, app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("recording-preview-") }.toSet())
        assertArrayEquals(before, digest(app, source))
        println("EXPORT_CANCEL_CLEANUP_OK session=$id")
    }

    private fun digest(app: Context, uri: Uri): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }

    private fun magentaPixels(bitmap: Bitmap): Int {
        val height = (bitmap.height * .55).toInt()
        val pixels = IntArray(bitmap.width * height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, height)
        return pixels.count { color ->
            val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
            r > 170 && b > 170 && g < 100
        }
    }
}
