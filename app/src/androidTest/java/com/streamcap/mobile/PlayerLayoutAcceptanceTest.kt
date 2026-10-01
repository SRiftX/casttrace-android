package com.streamcap.mobile

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.ui.player.PlaybackProgress
import com.streamcap.mobile.ui.player.PlaybackProgressStore
import com.streamcap.mobile.ui.player.PlaybackOptions
import com.streamcap.mobile.ui.player.RecordingPlayerScreen
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerLayoutAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun videoOwnsWindowAndControlsWorkOnDemand() {
        val id = InstrumentationRegistry.getArguments().getString("recordingId")?.toLongOrNull()
        assumeTrue("Requires an explicit saved real recording", id != null)
        val app = compose.activity.application as StreamCapApplication
        val store = PlaybackProgressStore(app)
        val previous = store.read(id!!)
        val previousOptions = store.readOptions()
        val previousDanmaku = store.readDanmakuVisible()
        store.writeOptions(PlaybackOptions())
        store.write(id, PlaybackProgress(0, true))
        try {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { RecordingPlayerScreen(id, app.container) {} }
            }
            compose.waitUntil(60_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { ready = findPlayer(it.window.decorView)?.player?.playbackState == Player.STATE_READY }
                ready
            }
            compose.activityRule.scenario.onActivity { activity ->
                val view = requireNotNull(findPlayer(activity.window.decorView))
                assertTrue("Video must occupy almost all width", view.width >= activity.window.decorView.width * .95)
                assertTrue("Video must occupy almost all height", view.height >= activity.window.decorView.height * .95)
                assertNull(view.player!!.playerError)
            }
            compose.mainClock.autoAdvance = false
            // Keep UI assertions deterministic while real danmaku is driven by the media clock.
            compose.activityRule.scenario.onActivity { findPlayer(it.window.decorView)!!.player!!.pause() }
            Thread.sleep(400)
            compose.mainClock.advanceTimeBy(500)
            if (compose.onAllNodesWithContentDescription("播放设置").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription("播放画面").performTouchInput { click() }
                compose.mainClock.advanceTimeBy(500)
            }
            compose.onNodeWithContentDescription("播放").assertIsDisplayed()
            compose.onNodeWithText("视频信息").assertDoesNotExist()
            compose.onNodeWithContentDescription("播放设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("倍速 1.0x").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("1.5x").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.activityRule.scenario.onActivity { assertEquals(1.5f, findPlayer(it.window.decorView)!!.player!!.playbackParameters.speed) }
            compose.onNodeWithContentDescription("播放设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("音量").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("播放音量").performTouchInput { click(centerLeft) }
            compose.activityRule.scenario.onActivity { assertTrue(findPlayer(it.window.decorView)!!.player!!.volume < .1f) }
            compose.onNodeWithText("完成").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("快进10秒").performClick()
            compose.activityRule.scenario.onActivity { assertTrue(findPlayer(it.window.decorView)!!.player!!.currentPosition >= 9000) }
            compose.onNodeWithContentDescription("全屏").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("退出全屏").assertIsDisplayed()
            compose.onNodeWithContentDescription("退出全屏").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("全屏").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("弹幕设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("弹幕大小").assertIsDisplayed()
            compose.onNodeWithText("完成").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("隐藏弹幕").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.activityRule.scenario.onActivity { it.setContent {} }
            compose.mainClock.advanceTimeBy(500)
            assertTrue(store.read(id).positionMs >= 9000)
            assertFalse(store.read(id).playWhenReady)
            assertEquals(1.5f, store.readOptions().speed)
            assertTrue(store.readOptions().volume < .1f)
            assertFalse(store.readDanmakuVisible())
            compose.mainClock.autoAdvance = true
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { RecordingPlayerScreen(id, app.container) {} }
            }
            compose.waitUntil(60_000) {
                var ready = false
                compose.activityRule.scenario.onActivity { ready = findPlayer(it.window.decorView)?.player?.playbackState == Player.STATE_READY }
                ready
            }
            compose.activityRule.scenario.onActivity {
                val player = requireNotNull(findPlayer(it.window.decorView)?.player)
                assertFalse(player.playWhenReady)
                assertTrue(player.currentPosition >= 9000)
                assertEquals(1.5f, player.playbackParameters.speed)
                assertTrue(player.volume < .1f)
            }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("显示弹幕").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("弹幕设置").assertIsDisplayed()
            compose.onNodeWithText("弹幕设置").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithText("完成").assertIsDisplayed()
            compose.onNodeWithText("完成").performClick()
            compose.mainClock.advanceTimeBy(500)
            println("PLAYER_LAYOUT_OK videoFillsWindow=true speed=true volume=true seek=true fullscreen=true danmakuSettings=true")
            println("PLAYER_REOPEN_OK progress=true paused=true speed=true volume=true danmakuVisibility=true settingsReachable=true")
        } finally {
            compose.activityRule.scenario.onActivity { it.setContent {} }
            compose.mainClock.advanceTimeBy(500)
            store.write(id, previous)
            store.writeOptions(previousOptions)
            store.writeDanmakuVisible(previousDanmaku)
            compose.mainClock.autoAdvance = true
        }
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findPlayer(view.getChildAt(index))?.let { return it }
        return null
    }
}
