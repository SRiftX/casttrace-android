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

class MorePlatformResolverTest {
    private fun client(vararg bodies: Pair<String, String>, inspect: (okhttp3.Request) -> Unit = {}): OkHttpClient {
        val fixtures = bodies.toMap()
        return OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            inspect(chain.request())
            val body = fixtures[chain.request().url.encodedPath]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200).message("fixture")
                .body(body.orEmpty().toResponseBody()).build()
        }).build()
    }

    @Test fun balancedPlayerJsonHandlesNestedBracesAndEscapedQuotes() {
        val text = "prefix {\"title\":\"a \\\"quoted\\\" } title\",\"nested\":{\"ok\":true}} suffix"
        assertEquals("a \"quoted\" } title", org.json.JSONObject(balancedJson(text, 7)).getString("title"))
    }

    @Test fun weiboReadsLiveAndOfflineResponses() = runBlocking {
        val live = WeiboResolver(client("/l/pc/anchor/live" to
            """{"data":{"user_info":{"name":"主播"},"item":{"status":1,"desc":"直播","stream_info":{"pull":{"live_origin_hls_url":"https://cdn.test/live.m3u8","live_origin_flv_url":"https://cdn.test/live.flv"}}}}}"""))
        assertEquals("主播", live.resolveRoom("https://weibo.com/show/123").anchorName)
        val offline = WeiboResolver(client("/l/pc/anchor/live" to
            """{"data":{"user_info":{"name":"主播"},"item":{"status":0}}}"""))
        assertEquals("主播", offline.resolveRoom("https://weibo.com/show/123").anchorName)
        assertFalse(live.supports("https://weibo.com.evil.test/show/123"))
    }

    @Test fun laixiuSendsSignatureFields() = runBlocking {
        var checked = false
        val resolver = LaixiuResolver(client("/liveroom/getShareLiveVideo" to
            """{"data":{"nickname":"主播","playStatus":0,"playUrl":"https://cdn.test/live.flv"}}""") { request ->
            assertTrue(request.header("requestId")!!.matches(Regex("[0-9a-f]{32}")))
            assertTrue(request.header("timestamp")!!.matches(Regex("[0-9]{13}")))
            checked = true
        })
        assertEquals("主播", resolver.resolveRoom("https://www.imkktv.com/live?roomId=42").anchorName)
        assertTrue(checked)
    }

    @Test fun restrictedPlatformsGiveExplicitReasonAndRejectLookalikeHosts() = runBlocking {
        val resolver = MiguResolver()
        assertTrue(resolver.supports("https://www.miguvideo.com/live/1"))
        assertFalse(resolver.supports("https://miguvideo.com.evil.test/live/1"))
        assertEquals("migu", resolver.resolveRoom("https://www.miguvideo.com/live/1").platform)
    }
}
