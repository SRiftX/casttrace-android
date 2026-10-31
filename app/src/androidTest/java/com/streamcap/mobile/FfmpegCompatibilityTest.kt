package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import org.junit.Assert.assertTrue
import org.junit.Test
import com.streamcap.mobile.recording.mp4.RecordingMp4Remuxer
import com.streamcap.mobile.ui.player.RecordingPlaybackSourceResolver
import android.media.MediaExtractor
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import com.streamcap.mobile.service.RecordingService
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@dagger.hilt.android.testing.HiltAndroidTest
class FfmpegCompatibilityTest : HiltGraphTest() {
    @Test fun nativeLibrariesLoad() {
        val session = FFmpegKit.executeWithArguments(arrayOf("-version"))
        assertTrue(session.failStackTrace ?: session.output, ReturnCode.isSuccess(session.returnCode))
        android.util.Log.i("FfmpegCompatibility", "PASS native libraries loaded")
    }

    @Test fun retainedFlvConvertsAndPreviewSeeks(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val path = InstrumentationRegistry.getArguments().getString("flvPath")
        org.junit.Assume.assumeTrue("Explicit retained FLV required", path != null)
        val source = File(path!!)
        val originalSize = source.length()
        val output = File(context.cacheDir, "qa-flv-remux.mp4")
        val resolver = RecordingPlaybackSourceResolver(context)
        try {
            val tracks = RecordingMp4Remuxer.convert(source, output)
            assertTrue(tracks.videoTracks > 0)
            assertTrue(tracks.audioTracks > 0)
            assertTrue(tracks.durationSeconds > 5)
            verifySeek(output, tracks.durationSeconds * 500_000)
            val preview = resolver.resolve(null, emptyList(), source.path, "MP4")
            assertEquals(1, preview.size)
            verifySeek(File(preview.single().path!!), tracks.durationSeconds * 500_000)
            assertEquals(originalSize, source.length())
            android.util.Log.i("FfmpegCompatibility", "PASS retained FLV size=$originalSize tracks=$tracks output=${output.length()}")
        } finally { resolver.close(); output.delete() }
    }

    @Test fun liveMp4RecordingPublishesAndSeeks(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val roomId = args.getString("roomId")?.toLongOrNull()
        org.junit.Assume.assumeTrue("Explicit live room required", roomId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext).testGraph.repository
        val baseline = repository.recordings.first().maxOfOrNull { it.id } ?: 0
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1500)
        withContext(Dispatchers.Main) { RecordingService.start(context, roomId!!) }
        val recording = try {
            val active = withTimeout(90_000) {
                repository.recordings.first { sessions -> sessions.any { it.id > baseline && it.roomId == roomId && it.totalBytes > 0 } }
                    .first { it.id > baseline && it.roomId == roomId }
            }
            assertEquals("MP4", active.containerFormat.uppercase())
            delay(45_000)
            active
        } finally {
            withContext(Dispatchers.Main) { context.startService(RecordingService.pauseIntent(context, roomId!!)) }
        }
        val saved = withTimeout(120_000) {
            repository.observeRecording(recording.id).first { it?.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED) }!!
        }
        assertTrue(saved.errorMessage, saved.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED))
        assertTrue(saved.outputUris.isNotEmpty())
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(saved.outputUris.first()), null)
            verifyExtractor(extractor, saved.durationSeconds * 500_000)
        } finally { extractor.release() }
        android.util.Log.i("FfmpegCompatibility", "PASS live MP4 session=${saved.id} duration=${saved.durationSeconds} uris=${saved.outputUris}")
    }

    @Test fun listConfiguredRoomsAndRecordings(): Unit = runBlocking {
        val repository = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext).testGraph.repository
        android.util.Log.i("FfmpegCompatibility", "ROOMS ${repository.rooms.first()}")
        android.util.Log.i("FfmpegCompatibility", "SESSIONS ${repository.recordings.first().map { "${it.id}:${it.roomId}:${it.status}:${it.containerFormat}:${it.temporaryPath}" }}")
        repository.logs.first().take(15).forEach { android.util.Log.i("FfmpegCompatibility", "EVENT ${it.sessionId} ${it.eventType} ${it.message}") }
    }

    @Test fun recoverFailedSession(): Unit = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("sessionId")?.toLongOrNull()
        org.junit.Assume.assumeTrue("Explicit failed recording required", id != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext).testGraph.repository
        val before = repository.session(id!!)!!
        assertTrue(before.status in setOf(TaskStatus.FAILED, TaskStatus.COMPLETED))
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(1000)
        if (before.status == TaskStatus.FAILED) RecordingService.recover(context, id)
        val saved = withTimeout(120_000) {
            repository.observeRecording(id).first { it?.status == TaskStatus.COMPLETED }!!
        }
        assertTrue(saved.outputUris.isNotEmpty())
        android.util.Log.i("FfmpegCompatibility", "PASS recovered session=$id duration=${saved.durationSeconds} uris=${saved.outputUris}")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun savedMp4DecodesAndSeeks() {
        val id = InstrumentationRegistry.getArguments().getString("sessionId")?.toLongOrNull()
        org.junit.Assume.assumeTrue("Explicit recording required", id != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val saved = runBlocking { app.testGraph.repository.session(id!!)!! }
        val texture = android.graphics.SurfaceTexture(false)
        texture.setDefaultBufferSize(1920, 1080)
        val surface = android.view.Surface(texture)
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        val frames = java.util.concurrent.atomic.AtomicInteger()
        try {
            instrumentation.runOnMainSync {
                player = androidx.media3.exoplayer.ExoPlayer.Builder(app).build().apply {
                    volume = 0f
                    setVideoSurface(surface)
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                    })
                    setMediaItem(androidx.media3.common.MediaItem.fromUri(saved.outputUris.first()))
                    prepare()
                }
            }
            fun waitReady() {
                val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
                var ready = false
                while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync { ready = player!!.playbackState == androidx.media3.common.Player.STATE_READY }
                    Thread.sleep(100)
                }
                assertTrue("Decoder did not become ready", ready)
            }
            waitReady()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            while (frames.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("Video decoder rendered no first frame", frames.get() > 0)
            var target = 0L
            val beforeSeekFrames = frames.get()
            instrumentation.runOnMainSync {
                assertNull(player!!.playerError)
                assertTrue(player!!.isCurrentMediaItemSeekable)
                target = player!!.duration / 2
                player!!.seekTo(target)
            }
            Thread.sleep(1000)
            waitReady()
            assertTrue("Video decoder rendered no frame after seek", frames.get() > beforeSeekFrames)
            instrumentation.runOnMainSync { player!!.play() }
            Thread.sleep(3000)
            instrumentation.runOnMainSync {
                assertNull(player!!.playerError)
                assertTrue(player!!.currentPosition > target + 1000)
                android.util.Log.i("FfmpegCompatibility", "PASS decoded session=$id duration=${player!!.duration} position=${player!!.currentPosition} frames=${frames.get()}")
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            surface.release(); texture.release()
        }
    }

    private fun verifySeek(file: File, targetUs: Long) {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(file.path); verifyExtractor(extractor, targetUs) }
        finally { extractor.release() }
    }

    private fun verifyExtractor(extractor: MediaExtractor, targetUs: Long) {
        val video = (0 until extractor.trackCount).first { extractor.getTrackFormat(it)
            .getString(android.media.MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
        extractor.selectTrack(video)
        assertTrue(extractor.readSampleData(java.nio.ByteBuffer.allocate(2 * 1024 * 1024), 0) > 0)
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        assertTrue("No key frame after seeking", extractor.sampleTime > 0)
        assertTrue(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
    }
}
