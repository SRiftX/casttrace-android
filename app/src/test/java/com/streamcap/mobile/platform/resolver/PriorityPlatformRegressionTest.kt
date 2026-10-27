package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PriorityPlatformRegressionTest {
    @Test fun inkeFindsCurrentStreamWhenShareApiOmitsLiveAddr() = runBlocking {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            paths += path
            val body = when (path) {
                "/web/live_share_pc" -> """{"data":{"status":1,"media_info":{"nick":"caster"},"records":[{"record_url":"https://cdn.test/replay.flv"}]}}"""
                "/web/Live_top_pc" -> """{"data":{"list":[]}}"""
                else -> """{"data":{"list":{"recommend":[{"uid":"42","live_id":"123","stream_addr":"https://cdn.test/current.flv"}]}}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val resolver = InkeResolver(client)
        val info = resolver.resolveRoom("https://www.inke.cn/live?uid=42&id=123")
        val room = com.streamcap.mobile.domain.model.LiveRoom(1, info.normalizedUrl, info.normalizedUrl, info.platform,
            info.anchorName, null, true, false, "原画", "视频", "MP4", false, null, false, 1800, false, true, 60,
            liveStatus = com.streamcap.mobile.domain.model.LiveStatus.UNKNOWN,
            taskStatus = com.streamcap.mobile.domain.model.TaskStatus.PAUSED, lastCheckAt = null, createdAt = 0, updatedAt = 0)
        assertEquals("https://cdn.test/current.flv", resolver.getStreams(room).single().url)
        assertTrue(paths.contains("/web/Live_hot_pc"))
    }

    @Test fun inkeDirectoryNeverSubstitutesAnotherBroadcastOrReplay() {
        val list = org.json.JSONArray("""[{"uid":"42","live_id":"old","stream_addr":"https://cdn.test/old.flv"},{"uid":"other","live_id":"123","stream_addr":"https://cdn.test/other.flv"},{"uid":"42","live_id":"123","record_url":"https://cdn.test/replay.flv"}]""")
        assertTrue(InkeResolver.directorySources(list, "42", "123").isEmpty())
    }

    @Test fun maoerUpgradesPublicMediaUrlsInsteadOfReportingLiveRoomWithoutSources() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"info":{"creator":{"username":"caster"},"room":{"status":{"broadcasting":true},"channel":{"flv_pull_url":"http://cdn.test/live.flv","hls_pull_url":"http://cdn.test/live.m3u8"}}}}""".toResponseBody()).build()
        }.build()
        val resolver = MaoerResolver(client)
        val info = resolver.resolveRoom("https://fm.missevan.com/live/123")
        val room = com.streamcap.mobile.domain.model.LiveRoom(1, info.normalizedUrl, info.normalizedUrl, info.platform,
            info.anchorName, null, true, false, "原画", "视频", "MP4", false, null, false, 1800, false, true, 60,
            liveStatus = com.streamcap.mobile.domain.model.LiveStatus.UNKNOWN,
            taskStatus = com.streamcap.mobile.domain.model.TaskStatus.PAUSED, lastCheckAt = null, createdAt = 0, updatedAt = 0)
        assertEquals(setOf("https://cdn.test/live.flv", "https://cdn.test/live.m3u8"), resolver.getStreams(room).map { it.url }.toSet())
    }

    @Test fun flextvStreamRequestUsesCurrentApiDomain() = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hosts += chain.request().url.host
            assertEquals("https://www.flextv.co.kr", chain.request().header("Origin"))
            val body = if (chain.request().url.host == "www.flextv.co.kr")
                """<script id="__NEXT_DATA__">{"props":{"pageProps":{"channelStream":{"channel":{"owner":{"nickname":"caster"}}}}}}</script>"""
            else """{"sources":[{"url":"https://cdn.test/live.m3u8"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        assertEquals("caster", FlexTvResolver(client).resolveRoom("https://www.ttinglive.com/channels/123/live").anchorName)
        assertEquals(listOf("www.flextv.co.kr", "api.flextv.co.kr"), hosts)
    }

    @Test fun rednoteUndefinedTokensDoNotRewriteSignedUrlsOrTitles() {
        val state = org.json.JSONObject(RednoteResolver.normalizeState("""{"missing":undefined,"title":"undefined","url":"https://cdn.test/undefined.flv?token=undefined"}"""))
        assertTrue(state.isNull("missing"))
        assertEquals("undefined", state.getString("title"))
        assertEquals("https://cdn.test/undefined.flv?token=undefined", state.getString("url"))
    }

    @Test fun acfunAcceptsObjectAdaptationSetFromLiveApi() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when (chain.request().url.encodedPath) {
                "/rest/pc-direct/user/userInfo" -> """{"profile":{"name":"主播","liveId":"live"}}"""
                "/rest/app/visitor/login" -> """{"userId":"visitor","acfun.api.visitor_st":"fixture"}"""
                else -> org.json.JSONObject().put("data", org.json.JSONObject().put("videoPlayRes",
                    """{"liveAdaptiveManifest":[{"adaptationSet":{"representation":[{"url":"https://cdn.test/live.flv"}]}}]}""")).toString()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val resolver = AcfunResolver(client)
        val info = resolver.resolveRoom("https://live.acfun.cn/live/123")
        val room = com.streamcap.mobile.domain.model.LiveRoom(1, info.normalizedUrl, info.normalizedUrl, info.platform,
            info.anchorName, null, true, false, "原画", "视频", "MP4", false, null, false, 1800, false, true, 60,
            liveStatus = com.streamcap.mobile.domain.model.LiveStatus.UNKNOWN,
            taskStatus = com.streamcap.mobile.domain.model.TaskStatus.PAUSED, lastCheckAt = null, createdAt = 0, updatedAt = 0)
        assertEquals("https://cdn.test/live.flv", resolver.getStreams(room).single().url)
    }

    @Test fun picartoUsesApiRecommendedEdgeBeforeLegacyFallback() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"channel":{"name":"artist","online":true},"getLoadBalancerUrl":{"url":"http://edge1-us-losangeles.picarto.tv"}}""".toResponseBody()).build()
        }.build()
        val resolver = PicartoResolver(client)
        val info = resolver.resolveRoom("https://picarto.tv/artist")
        // Resolve through a real domain model, so this checks the public getStreams contract.
        val room = com.streamcap.mobile.domain.model.LiveRoom(1, info.normalizedUrl, info.normalizedUrl, info.platform,
            info.anchorName, null, true, false, "原画", "视频", "MP4", false, null, false, 1800, false, true, 60,
            liveStatus = com.streamcap.mobile.domain.model.LiveStatus.UNKNOWN,
            taskStatus = com.streamcap.mobile.domain.model.TaskStatus.PAUSED, lastCheckAt = null, createdAt = 0, updatedAt = 0)
        val sources = resolver.getStreams(room)
        assertEquals("edge1-us-losangeles.picarto.tv", sources.first().url.toHttpUrlOrNull()?.host)
        assertTrue(sources.first().url.startsWith("https://"))
        assertTrue(sources.size >= 2)
    }
    @Test fun kuaishouRateLimitIsAnErrorRatherThanOffline() {
        val result = runCatching { KuaishouResolver.initialState("""<script>window.__INITIAL_STATE__ = {"liveroom":{"playList":[{"liveStream":{},"errorType":{"type":2,"title":"请求过快，请稍后重试"}}]}};window.other = {};</script>""") }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("请求过快"))
        assertFalse(KuaishouResolver(OkHttpClient()).supports("https://live.kuaishou.com.evil.test/u/x"))
    }

    @Test fun kuaishouBalancedStateIgnoresFollowingJavascript() {
        val root = KuaishouResolver.initialState("""window.__INITIAL_STATE__ = {"title":"escaped } brace","liveroom":{}};(function(){})();""")
        assertEquals("escaped } brace", root.getString("title"))
    }

    @Test fun rednoteAppDeeplinkRetainsEncodedMediaQuery() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """<script>window.__INITIAL_STATE__={"liveStream":{"liveStatus":"success","roomData":{"roomInfo":{"roomTitle":"直播","deeplink":"xhsdiscover://live/room?flvUrl=http%3A%2F%2Fcdn.test%2Flive.flv%3Ftoken%3Da%252Bb&host_nickname=%E4%B8%BB%E6%92%AD"}}}};</script>"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        assertEquals("主播", RednoteResolver(client).resolveRoom("https://app.xhs.cn/livestream/123").anchorName)
        val query = RednoteResolver.deeplinkQuery("xhsdiscover://live/room?flvUrl=https%3A%2F%2Fcdn.test%2Flive.flv%3Fx%3Da%252Bb")!!
        assertEquals("https://cdn.test/live.flv?x=a%2Bb", query.queryParameter("flvUrl"))
    }
}
