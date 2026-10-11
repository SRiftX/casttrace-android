package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPlatformResolverTest {
    private fun client(vararg responses: Pair<String, String>): OkHttpClient {
        val bodies = responses.toMap()
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = bodies[request.url.encodedPath]
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message("fixture")
                .body(body.orEmpty().toResponseBody()).build()
        }).build()
    }

    @Test fun maoerReadsPublicRoomAndRejectsLookalikeHost() = runBlocking {
        val resolver = MaoerResolver(client("/api/v2/live/123" to """{"info":{"creator":{"username":"主播"},"room":{"name":"直播","status":{"broadcasting":true},"channel":{"flv_pull_url":"https://cdn.example/live.flv","hls_pull_url":"https://cdn.example/live.m3u8"}}}}"""))
        assertEquals("主播", resolver.resolveRoom("https://fm.missevan.com/live/123").anchorName)
        assertFalse(resolver.supports("https://fm.missevan.com.evil.example/live/123"))
    }

    @Test fun showroomResolvesFriendlyRoomUrl() = runBlocking {
        val resolver = ShowroomResolver(client(
            "/alice" to """<a href="/room/profile?room_id=42">room</a>""",
            "/api/live/live_info" to """{"room_name":"Alice","live_status":0}""",
        ))
        assertEquals("Alice", resolver.resolveRoom("https://www.showroom-live.com/alice").anchorName)
    }

    @Test fun chzzkReadsPlaybackJson() = runBlocking {
        val resolver = ChzzkResolver(client("/service/v3/channels/458f6ec20b034f49e0fc6d03921646d2/live-detail" to
            """{"content":{"channel":{"channelName":"Channel"},"status":"OPEN","liveTitle":"Title","livePlaybackJson":"{\"media\":[{\"path\":\"https://cdn.example/live.m3u8\"}]}"}}"""))
        assertEquals("Channel", resolver.resolveRoom("https://chzzk.naver.com/live/458f6ec20b034f49e0fc6d03921646d2").anchorName)
        assertTrue(resolver.supports("https://chzzk.naver.com/live/458f6ec20b034f49e0fc6d03921646d2"))
    }

    @Test fun picartoReadsOnlineMetadata() = runBlocking {
        val resolver = PicartoResolver(client("/api/channel/detail/alice" to """{"channel":{"name":"alice","online":true,"title":"Drawing"}}"""))
        assertEquals("Drawing", resolver.resolveRoom("https://picarto.tv/alice").roomTitle)
    }

    @Test fun twitCastingReadsLiveHlsAndRejectsLookalikeHost() = runBlocking {
        val resolver = TwitCastingResolver(client(
            "/alice" to """<html><head><meta property="og:title" content="Alice Live"></head><body><div data-is-onlive="true"></div></body></html>""",
            "/streamserver.php" to """{"tc-hls":{"streams":{"high":"https://cdn.example/live.m3u8","low":"https://cdn.example/low.m3u8"}}}""",
        ))
        assertEquals("Alice Live", resolver.resolveRoom("https://twitcasting.tv/alice").roomTitle)
        assertFalse(resolver.supports("https://twitcasting.tv.evil.example/alice"))
    }

    @Test fun genericWebFindsPublicMediaWithoutAcceptingLookalikeHost() = runBlocking {
        val resolver = GenericWebResolver("sample", listOf("example.com"), client(
            "/room/1" to """<title>Live Room</title><script>var play="https://cdn.example.com/live.m3u8";</script>""",
        ))
        assertEquals("Live Room", resolver.resolveRoom("https://example.com/room/1").roomTitle)
        assertTrue(resolver.supports("https://live.example.com/room/1"))
        assertFalse(resolver.supports("https://example.com.evil.test/room/1"))
    }

    @Test fun langLiveReadsDedicatedRoomApi() = runBlocking {
        val resolver = LangLiveResolver(client("/langweb/v1/room/liveinfo" to
            """{"data":{"live_info":{"nickname":"Anchor","live_status":1,"liveurl_hls":"https://cdn.example/live.m3u8","liveurl":"https://cdn.example/live.flv"}}}"""))
        assertEquals("Anchor", resolver.resolveRoom("https://www.lang.live/123").anchorName)
    }

    @Test fun yiqiLiveReadsDedicatedRoomApi() = runBlocking {
        val resolver = YiqiLiveResolver(client(
            "/api/v1/user/room/123" to """{"displayName":"Anchor"}""",
            "/api/v1/lives/123/viewers/alive" to """{"status":2,"pullURLsInfo":{"rtmpURLs":[{"urlHighQuality":"https://cdn.example/live.flv"}]}}""",
        ))
        assertEquals("Anchor", resolver.resolveRoom("https://17.live/123").anchorName)
    }

    @Test fun kugouReadsDedicatedPlaybackApi() = runBlocking {
        val resolver = KugouResolver(client(
            "/roomcen/room/web/cdn/getEnterRoomInfo" to """{"data":{"normalRoomInfo":{"nickName":"Anchor"},"liveType":1}}""",
            "/video/pc/live/pull/mutiline/streamaddr" to """{"data":{"lines":[{"streamProfiles":[{"httpsFlv":["https://cdn.example/live.flv"]}]}]}}""",
        ))
        assertEquals("Anchor", resolver.resolveRoom("https://fanxing.kugou.com/123").anchorName)
    }

    @Test fun inkeReadsDedicatedPlaybackApi() = runBlocking {
        val resolver = InkeResolver(client("/web/live_share_pc" to
            """{"data":{"media_info":{"nick":"Anchor"},"status":1,"live_addr":[{"hls_stream_addr":"https://cdn.example/live.m3u8","stream_addr":"https://cdn.example/live.flv"}]}}"""))
        assertEquals("Anchor", resolver.resolveRoom("https://www.inke.cn/live?uid=42&id=123").anchorName)
    }

    @Test fun neteaseCcReadsNextData() = runBlocking {
        val resolver = NeteaseCcResolver(client("/123/" to
            """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"roomInfoInitData":{"live":{"status":1,"nickname":"Anchor","title":"Live","sharefile":"https://cdn.example/live.m3u8"}}}}}</script>"""))
        assertEquals("Anchor", resolver.resolveRoom("https://cc.163.com/123").anchorName)
    }

    @Test fun bluedReadsEmbeddedPageData() = runBlocking {
        val json = """{"userInfo":{"name":"Anchor","onLive":true},"liveInfo":{"liveUrl":"https://cdn.example/live.m3u8"}}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        val resolver = BluedResolver(client("/room/123" to """<script>decodeURIComponent("$encoded"),window.Promise</script>"""))
        assertEquals("Anchor", resolver.resolveRoom("https://blued.cn/room/123").anchorName)
    }

    @Test fun bigoReadsStudioApi() = runBlocking {
        val resolver = BigoResolver(client("/official_website/studio/getInternalStudioInfo" to
            """{"data":{"nick_name":"Anchor","alive":1,"hls_src":"https://cdn.example/live.m3u8"}}"""))
        assertEquals("Anchor", resolver.resolveRoom("https://www.bigo.tv/123").anchorName)
    }

    @Test fun pandaReadsMemberAndPlaylistApis() = runBlocking {
        val resolver = PandaResolver(client(
            "/v1/member/bj" to """{"bjInfo":{"id":"123","nick":"Anchor"},"media":{}}""",
            "/v1/live/play" to """{"PlayList":{"hls":[{"url":"https://cdn.example/live.m3u8"}]}}""",
        ))
        assertEquals("Anchor", resolver.resolveRoom("https://www.pandalive.co.kr/123").anchorName)
    }
}
