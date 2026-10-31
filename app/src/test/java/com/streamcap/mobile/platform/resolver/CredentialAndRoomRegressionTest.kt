package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.PlatformCredential
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class CredentialAndRoomRegressionTest {
    private fun client(body: (okhttp3.Request) -> String) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body(request).toResponseBody()).build()
        }.build()

    @Test fun domesticMetadataReceivesCookie() = runBlocking {
        var requests = 0
        val resolver = WeiboResolver(client {
            assertEquals("session=domestic", it.header("Cookie"))
            requests++
            """{"data":{"user_info":{"name":"Anchor"},"item":{"status":0}}}"""
        })
        assertTrue(resolver.supportsCredentials)
        assertEquals("Anchor", resolver.resolveRoom("https://weibo.com/show/123",
            PlatformCredential("weibo", "session=domestic")).anchorName)
        assertEquals(1, requests)
    }

    @Test fun foreignMetadataReceivesCookieAndAnonymousCallStillWorks() = runBlocking {
        val cookies = mutableListOf<String?>()
        val resolver = GenericWebResolver("sample", listOf("example.com"), client {
            cookies += it.header("Cookie")
            "<title>Room</title>"
        })
        assertTrue(resolver.supportsCredentials)
        resolver.resolveRoom("https://example.com/alice", PlatformCredential("sample", "session=foreign"))
        resolver.resolveRoom("https://example.com/alice")
        assertEquals(listOf("session=foreign", null), cookies)
    }

    @Test fun flexCarriesDirectoryTokenToChildRequestsAndKeepsExternalAudio() = runBlocking {
        val resolver = FlexTvResolver(client {
            if (it.url.host == "www.flextv.co.kr")
                """<script id="__NEXT_DATA__">{"props":{"pageProps":{"channelStream":{"channel":{"owner":{"nickname":"Anchor"}}}}}}</script>"""
            else """{"sources":[{"resolution":1080,"url":"https://cdn.edge.naverncp.com/live/high.m3u8?token=signed"},{"resolution":480,"url":"https://cdn.edge.naverncp.com/live/low.m3u8?token=signed"},{"resolution":0,"url":"https://cdn.edge.naverncp.com/live/audio.m3u8?token=signed"}]}"""
        })
        val room = testRoom("https://www.flextv.co.kr/channels/123/live").copy(quality = "流畅")
        val sources = resolver.getStreams(room, PlatformCredential("flextv", "account=kept"))
        assertEquals(1, sources.size)
        assertTrue(sources.single().url.contains("low.m3u8"))
        assertEquals("account=kept; token=signed", sources.single().headers["Cookie"])
        assertTrue(sources.single().separateAudio)
    }

    @Test fun huajiaoAcceptsOfficialPathAndShareQueryWithoutChangingRoom() = runBlocking {
        val ids = mutableListOf<String?>()
        val resolver = HuajiaoResolver(client {
            ids += it.url.queryParameter("relateid")
            """{"data":{"author":{"nickname":"Anchor"},"feed":{}}}"""
        })
        assertEquals("Anchor", resolver.resolveRoom("https://www.huajiao.com/l/400607857").anchorName)
        resolver.resolveRoom("https://www.huajiao.com/share?liveid=400607857")
        assertEquals(listOf("400607857", "400607857"), ids)
        assertTrue(runCatching { resolver.resolveRoom("https://www.huajiao.com/share?liveid=bad") }.isFailure)
        assertEquals(2, ids.size)
    }

    @Test fun showroomRecognizesLiveWithOnlyConcreteHlsVariants() = runBlocking {
        val resolver = ShowroomResolver(client {
            if (it.url.encodedPath.endsWith("live_info")) """{"room_name":"Alice","live_status":2}"""
            else """{"streaming_url_list":[{"type":"webrtc","url":"https://cdn.test/rtc"},{"type":"hls","quality":1000,"url":"https://cdn.test/main.m3u8"},{"type":"hls","quality":100,"url":"https://cdn.test/low.m3u8"}]}"""
        })
        // Previously a live room without hls_all was treated as offline.
        val room = testRoom("https://www.showroom-live.com/42")
        assertEquals(com.streamcap.mobile.domain.model.LiveStatus.LIVE, resolver.checkLive(room, null))
        assertEquals("https://cdn.test/low.m3u8", resolver.getStreams(room.copy(quality = "流畅"), null).first().url)
        assertEquals("https://cdn.test/main.m3u8", resolver.getStreams(room.copy(quality = "原画"), null).first().url)
    }

    @Test fun youtubeLiveWithoutHlsAndLoginRestrictionAreErrorsRatherThanOffline() = runBlocking {
        for (body in listOf(
            """{"videoDetails":{"isLive":true},"playabilityStatus":{"status":"OK"},"streamingData":{"serverAbrStreamingUrl":"https://cdn.test/sabr"}}""",
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""",
        )) {
            val resolver = YouTubeResolver(client { "<script>var ytInitialPlayerResponse=$body;</script>" })
            assertTrue(runCatching { resolver.resolveRoom("https://www.youtube.com/@news/live") }.isFailure)
        }
    }

    @Test fun bigoLoginRequiredDoesNotMasqueradeAsOfflineAndReceivesCookie() = runBlocking {
        val resolver = BigoResolver(client {
            assertEquals("session=account", it.header("Cookie"))
            """{"data":{"alive":0,"needLogin":true}}"""
        })
        assertTrue(resolver.supportsCredentials)
        val result = runCatching { resolver.resolveRoom("https://www.bigo.tv/219878136", PlatformCredential("bigo", "session=account")) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Cookie"))
    }

    @Test fun showroomRetainsAdaptiveFallbackWhenConcreteLowVariantFails() = runBlocking {
        val resolver = ShowroomResolver(client {
            if (it.url.encodedPath.endsWith("live_info")) """{"room_name":"Alice","live_status":2}"""
            else """{"streaming_url_list":[{"type":"hls_all","url":"https://cdn.test/master.m3u8"},{"type":"hls","quality":1000,"url":"https://cdn.test/high.m3u8"},{"type":"hls","quality":100,"url":"https://cdn.test/low.m3u8"}]}"""
        })
        assertEquals(listOf("https://cdn.test/low.m3u8", "https://cdn.test/master.m3u8", "https://cdn.test/high.m3u8"),
            resolver.getStreams(testRoom("https://www.showroom-live.com/42").copy(quality = "流畅"), null).map { it.url })
    }

    private fun testRoom(url: String) = com.streamcap.mobile.domain.model.LiveRoom(
        1, url, url, "showroom", "Alice", null, true, false, "原画", "视频", "MP4", false,
        null, false, 3600, false, false, 30, liveStatus = com.streamcap.mobile.domain.model.LiveStatus.UNKNOWN,
        taskStatus = com.streamcap.mobile.domain.model.TaskStatus.WAITING, lastCheckAt = null, createdAt = 0, updatedAt = 0,
    )
}
