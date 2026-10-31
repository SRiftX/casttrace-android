package com.streamcap.mobile

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.ui.player.RecordingPlayerScreen
import com.streamcap.mobile.ui.theme.StreamCapTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@dagger.hilt.android.testing.HiltAndroidTest
class PlaybackAcceptanceTest : HiltGraphTest() {
    @get:Rule(order = Int.MIN_VALUE + 1)
    val runtimePermissions = mainActivityRuntimePermissionsRule()

    @Test fun savedRecordingSeeksAndPlaysAtDoubleSpeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val selectedId = resolvePlaybackRecordingId(app, "playbackRecordingId")
        assumeTrue("Requires a saved recording of at least ten minutes", selectedId != null)
        val id = requireNotNull(selectedId)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { StreamCapTheme { RecordingPlayerScreen(id) {} } }
            }
            var player: Player? = null
            val deadline = android.os.SystemClock.elapsedRealtime() + 60000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    player = findPlayer(activity.window.decorView)
                    ready = player?.playbackState == Player.STATE_READY
                }
                Thread.sleep(200)
            }
            assertTrue("Player never became ready", ready)
            scenario.onActivity {
                assertNull(player!!.playerError)
                assertTrue("Saved recording must be seekable", player!!.isCurrentMediaItemSeekable)
                assertTrue("Expected ten-minute media", player!!.duration >= 600000)
                player!!.pause()
                player!!.seekTo(300000)
            }
            Thread.sleep(2000)
            scenario.onActivity {
                assertTrue(kotlin.math.abs(player!!.currentPosition - 300000) < 1000)
                player!!.setPlaybackSpeed(2f)
                player!!.play()
            }
            Thread.sleep(5000)
            scenario.onActivity {
                val advanced = player!!.currentPosition - 300000
                assertTrue("2x playback advanced $advanced ms in 5 seconds", advanced in 7500..12000)
                assertNull(player!!.playerError)
                player!!.pause()
                player!!.seekTo(player!!.duration - 10000)
            }
            Thread.sleep(2000)
            scenario.onActivity {
                assertTrue(kotlin.math.abs(player!!.duration - 10000 - player!!.currentPosition) < 1000)
                assertNull(player!!.playerError)
                android.util.Log.i("PlaybackAcceptance", "PASS duration=${player!!.duration} seek=${player!!.currentPosition} speed=${player!!.playbackParameters.speed}")
            }
        }
    }

    private fun findPlayer(view: View): Player? {
        if (view is PlayerView) return view.player
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
