package com.streamcap.mobile

import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuFrame
import com.streamcap.mobile.danmaku.DouyinDanmakuClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class DouyinRealConnectionTest {
    @Ignore("需要用户提供当前正在直播的抖音房间；此固定房间可能已下播")
    @Test fun connectsToDouyinWebsocketWithRealProtocol() = runBlocking {
        val client = DouyinDanmakuClient(OkHttpClient())
        try {
            val frame = withTimeout(20_000) {
                client.connect(DanmakuConnectContext(
                    sessionId = 9001,
                    platform = "douyin",
                    roomUrl = "https://live.douyin.com/7592278867031231283",
                )).first { it is DanmakuFrame.Status && it.state == "connected-real" }
            }
            assertEquals("connected-real", (frame as DanmakuFrame.Status).state)
        } finally {
            client.close("test-finished")
        }
    }
}
