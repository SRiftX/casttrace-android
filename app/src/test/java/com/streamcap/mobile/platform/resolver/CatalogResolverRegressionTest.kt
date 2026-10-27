package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Contract cases for the less popular paths; these do not claim live-site acceptance. */
class CatalogResolverRegressionTest {
    private fun client(body: (okhttp3.Request) -> String) = OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body(it.request()).toResponseBody()).build()
    }.build()
    private fun room(url: String) = LiveRoom(1, url, url, "fixture", "Anchor", null, true, false,
        "原画", "视频", "MP4", false, null, false, 3600, false, false, 30,
        liveStatus = LiveStatus.UNKNOWN, taskStatus = TaskStatus.WAITING, lastCheckAt = null, createdAt = 0, updatedAt = 0)
    private suspend fun expectStream(resolver: PlatformResolver, url: String) {
        assertTrue(resolver.supports(url))
        assertFalse(resolver.supports(url.replace(Regex("(https://[^/]+)"), "$1.evil.test")))
        assertEquals(LiveStatus.LIVE, resolver.checkLive(room(url)))
        assertTrue(resolver.getStreams(room(url)).any { it.url == "https://cdn.test/live.m3u8" || it.url == "https://cdn.test/live.flv" })
    }

    @Test fun weiboPreservesCompositeLiveId() = runBlocking {
        val resolver = WeiboResolver(client {
            assertEquals("1022:2321325026370190442592", it.url.queryParameter("live_id"))
            """{"data":{"item":{"status":1,"stream_info":{"pull":{"live_origin_hls_url":"https://cdn.test/live.m3u8"}}}}}"""
        })
        expectStream(resolver, "https://weibo.com/l/wblive/p/show/1022:2321325026370190442592")
    }
    @Test fun xindongJavascriptConfigKeepsUrlsAndRemovesCommentsAndTrailingComma() = runBlocking {
        val resolver = XindongreboResolver(client {
            """<script>var user = {"zb_nickname":"A // literal", /* comment */
                // platform comment
                "play_url":"https://cdn.test/live.flv?x=1",};</script>"""
        })
        assertEquals("A // literal", resolver.resolveRoom("https://xcqrkj.com/web/video.php?roomnumber=123").anchorName)
        assertEquals("https://cdn.test/live.flv?x=1", resolver.getStreams(room("https://xcqrkj.com/web/video.php?roomnumber=123")).single().url)
    }
    @Test fun baiduFallsBackFromEmptyFlvToHls() = runBlocking {
        expectStream(BaiduLiveResolver(client {
            """{"data":{"room":{"status":"0","video":{"url_clarity_list":[{"urls":{"flv":"","hls":"https://cdn.test/live.m3u8"}}]}}}}"""
        }), "https://live.baidu.com/live.html?room_id=123")
    }
    @Test fun bluedReadsJsonParseWrapperWithoutDependingOnFollowingScript() = runBlocking {
        val json = """{"userInfo":{"name":"Anchor","onLive":true},"liveInfo":{"liveUrl":"https://cdn.test/live.m3u8"}}"""
        val encoded = java.net.URLEncoder.encode(json, "UTF-8")
        val resolver = BluedResolver(client { "<script>window.CONFIG=JSON.parse(decodeURIComponent(\"$encoded\"));</script>" })
        expectStream(resolver, "https://app.blued.cn/live?id=Mp6G2R")
        assertEquals("https://app.blued.cn/", resolver.getStreams(room("https://app.blued.cn/live?id=Mp6G2R")).single().headers["Referer"])
    }
    @Test fun changliaoComposesOnlyCurrentLiveId() = runBlocking {
        expectStream(ChangliaoResolver(client {
            if (it.url.encodedPath.endsWith("live.ashx")) """{"data":{"roomInfo":{"live_stat":1,"liveID":"live"}}}"""
            else """var config = {"domainpullstream_flv":"https://cdn.test"};"""
        }), "https://live.tlclw.com/106188")
    }
    @Test fun vvxqReadsCurrentVideo() = runBlocking {
        expectStream(VvxqResolver(client { """{"status":100,"videoUrl":"https://cdn.test/live.m3u8"}""" }),
            "https://h5webcdn-pro.vvxqiu.com/share?roomId=LP115924473")
    }
    @Test fun lianjieReadsOnlineVideo() = runBlocking {
        expectStream(LianjieResolver(client { """{"data":{"isonline":1,"videoUrl":"https://cdn.test/live.flv"}}""" }),
            "https://show.lailianjie.com/10000258")
    }
    @Test fun piaopiaoAndHuamaoUseTheirOwnPreviewApisAndOrigin() = runBlocking {
        for ((host, url) in listOf("api.pp.weimipopo.com" to "https://m.pp.weimipopo.com/live/preview.html?anchorUid=42",
            "api.catshow168.com" to "https://h.catshow168.com/live/preview.html?anchorUid=42")) {
            val http = client {
                assertEquals(host, it.url.host)
                assertNotNull(it.header("Origin"))
                """{"data":{"name":"Anchor","living":true,"pullUrl":"https://cdn.test/live.m3u8"}}"""
            }
            expectStream(if (host.contains("catshow")) HuamaoResolver(http) else PiaopiaoResolver(http), url)
        }
    }
    @Test fun zhihuReadsActiveDrama() = runBlocking {
        expectStream(ZhihuResolver(client {
            """<script id="js-initialData">{"initialState":{"theater":{"theaters":{"10687":{"drama":{"status":1,"playInfo":{"hlsUrl":"https://cdn.test/live.m3u8"}}}}}}}</script>"""
        }), "https://www.zhihu.com/theater/10687")
    }
    @Test fun jdDoesNotReturnStaleVideoAfterBroadcastEnds() = runBlocking {
        val resolver = JdResolver(client {
            if (it.url.host == "lives.jd.com") "<html/>"
            else """{"data":{"status":0,"videoUrl":"https://cdn.test/live.flv"}}"""
        })
        assertEquals(LiveStatus.OFFLINE, resolver.checkLive(room("https://lives.jd.com/?liveId=12345")))
        assertTrue(resolver.getStreams(room("https://lives.jd.com/?liveId=12345")).isEmpty())
    }
    @Test fun soopRequiresAuthorizationAndKeepsAidQuery() = runBlocking {
        val resolver = SoopResolver(client {
            when (it.url.encodedPath) {
                "/afreeca/player_live_api.php" -> {
                    val form = it.body as okhttp3.FormBody
                    if ((0 until form.size).any { n -> form.name(n) == "type" && form.value(n) == "aid" })
                        """{"CHANNEL":{"AID":"token+value"}}"""
                    else """{"CHANNEL":{"RESULT":1,"VIEWPRESET":"normal","BNO":"12345","TITLE":"Live"}}"""
                }
                else -> """{"view_url":"https://cdn.test/live.m3u8?existing=1"}"""
            }
        })
        val stream = resolver.getStreams(room("https://play.sooplive.com/anchor/12345")).single()
        assertTrue(stream.url.contains("existing=1&aid=token%2Bvalue"))
        val restricted = SoopResolver(client { """{"CHANNEL":{"RESULT":-6}}""" })
        assertTrue(runCatching { restricted.getStreams(room("https://play.sooplive.com/anchor")) }.isFailure)
    }
    @Test fun plannedPlatformsFailExplicitlyInsteadOfReturningEmptyStreams() = runBlocking {
        for ((resolver,url) in listOf(LiveMeResolver() to "https://liveme.com/live/1", HaixiuResolver() to "https://haixiutv.com/1",
            LehaiResolver() to "https://lehaitv.com/1", MiguResolver() to "https://miguvideo.com/live/1", PopkonResolver() to "https://popkontv.com/live/1")) {
            assertTrue(resolver.supports(url))
            resolver.resolveRoom(url)
            assertTrue(runCatching { resolver.getStreams(room(url)) }.exceptionOrNull() is IllegalStateException)
        }
    }
}
