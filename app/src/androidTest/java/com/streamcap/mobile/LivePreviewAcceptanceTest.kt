package com.streamcap.mobile

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.StreamType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class LivePreviewAcceptanceTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun currentLineRendersFirstFrame() {
        val roomId = InstrumentationRegistry.getArguments().getString("previewRoomId")?.toLongOrNull()
        assumeTrue("Requires previewRoomId", roomId != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as StreamCapApplication
        val source = runBlocking { app.container.repository.streams(roomId!!).first() }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(1280, 720) }
        val surface = Surface(texture)
        val frames = AtomicInteger()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(app)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
                    .build().apply {
                        volume = 0f
                        setVideoSurface(surface)
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() { frames.incrementAndGet() }
                        })
                        setMediaItem(MediaItem.Builder().setUri(source.url)
                            .setMimeType(if (source.type == StreamType.HLS) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_FLV)
                            .build())
                        prepare()
                        play()
                    }
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 45_000
            while (frames.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(250)
                var failure: Exception? = null
                instrumentation.runOnMainSync { failure = player?.playerError }
                if (failure != null) throw AssertionError("Preview failed: ${failure!!.message}", failure)
            }
            instrumentation.runOnMainSync {
                assertNull(player!!.playerError)
                assertTrue("Live preview rendered no frame in 45 seconds", frames.get() > 0)
                android.util.Log.i("LivePreviewAcceptance", "PASS room=$roomId type=${source.type} frames=${frames.get()}")
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            surface.release()
            texture.release()
        }
    }
}
