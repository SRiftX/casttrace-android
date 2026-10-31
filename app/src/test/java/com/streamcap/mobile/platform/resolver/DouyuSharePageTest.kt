package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DouyuSharePageTest {
    @Test fun resolvesShareParametersWhenLegacyApiReturnsHtml() = runBlocking {
        val mobile = """<html><script id="vike_pageContext" type="application/json">{"pageProps":{"room":{"roomInfo":{"roomInfo":{"rid":1863767,"vipId":998,"nickname":"赛事主播","roomName":"决赛","isLive":1}}}}}</script></html>"""
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = if (chain.request().url.host == "m.douyu.com") mobile else "<html>blocked</html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }).build()
        val resolver = DouyuResolver(client)
        val info = resolver.resolveRoom("https://www.douyu.com/998?dyshid=abc&dyshti=123")
        assertEquals("https://www.douyu.com/998", info.normalizedUrl)
        assertEquals("赛事主播", info.anchorName)
        assertEquals("决赛", info.roomTitle)
        assertFalse(resolver.supports("https://example.com/?next=douyu.com/998"))
    }
}
