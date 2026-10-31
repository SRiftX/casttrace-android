package com.streamcap.mobile

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.streamcap.mobile.app.MainActivity
import com.streamcap.mobile.ui.rooms.AddRoomScreen
import com.streamcap.mobile.ui.theme.StreamCapTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

@dagger.hilt.android.testing.HiltAndroidTest
class ShareLinkAcceptanceTest : HiltGraphTest() {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun bilibiliFullShareText() = verify(
        "【青春制作人们 请pick我！-bilibili直播】 https://b23.tv/nyVZ9cs",
        "B站直播", "https://live.bilibili.com/22508985")

    @Test fun douyinFullShareText() = verify(
        "9- #在抖音，记录美好生活#【戟创官方旗舰店】正在直播，来和我一起支持Ta吧。复制下方链接，打开【抖音】，直接观看直播！ https://v.douyin.com/RWTjSDkAJRs/ 8@1.com :2pm",
        "抖音直播", "https://live.douyin.com/5634899055")

    @Test fun huyaShortShareLink() = verify("https://hy.fan/CH965Gr", "虎牙直播", "https://www.huya.com/528300")

    private fun verify(text: String, platform: String, url: String) {
        val app = compose.activity.application
        val existing = runBlocking { app.testGraph.database.liveRoomDao().getByNormalizedUrl(url) }
        val saved = AtomicLong()
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { StreamCapTheme { AddRoomScreen(null, {}, { saved.set(it) }) } }
        }
        // setContent runs on the main thread; wait until Compose has published its tree.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("URL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("URL").performTextReplacement(text)
        compose.onNodeWithText("识别链接并确认信息").performScrollTo().performClick()
        compose.waitUntil(90_000) {
            compose.onAllNodesWithText("识别结果：$platform", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val info = runBlocking { app.testGraph.repository.previewRoom(text).first }
        assertEquals(url, info.normalizedUrl)
        assertEquals(platform, info.platform)
        assertTrue(info.anchorName.isNotBlank())
        if (existing == null) {
            // Reinsert the full share text to exercise the save path independently of preview normalization.
            compose.onNodeWithText("URL").performScrollTo().performTextReplacement(text)
            compose.onNodeWithContentDescription("确认主播并添加").performClick()
            compose.waitUntil(90_000) { saved.get() > 0 }
            val room = runBlocking { app.testGraph.repository.room(saved.get())!! }
            assertEquals(url, room.normalizedUrl)
            assertEquals(platform, room.platform)
            assertEquals(info.anchorName, room.anchorName)
        } else {
            compose.onNodeWithText("这个直播间已存在，不会覆盖原设置").performScrollTo().assertIsDisplayed()
        }
        val roomId = saved.get().takeIf { it > 0 } ?: existing!!.id
        val room = runBlocking { app.testGraph.repository.room(roomId)!! }
        val status = runBlocking { app.testGraph.repository.recordingLiveStatus(roomId) }
        assertTrue("Canonical room must remain checkable: $status", status in setOf(
            com.streamcap.mobile.domain.model.LiveStatus.LIVE, com.streamcap.mobile.domain.model.LiveStatus.OFFLINE))
        android.util.Log.i("ShareLinkQA", "PASS $platform anchor=${info.anchorName} room=${room.normalizedUrl} status=$status saved=${saved.get()}")
    }
}
