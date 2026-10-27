package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShareLinkDiscoveryTest {
    private fun discovery(responses: Map<String, Pair<Int, String>>): ShareLinkDiscovery {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val (code, content) = responses[chain.request().url.toString()] ?: (404 to "")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test")
                .apply { if (code in 300..399) header("Location", content) }
                .body((if (code in 300..399) "" else content).toResponseBody()).build()
        }).build()
        return ShareLinkDiscovery(client, ResolverRegistry(listOf(HuyaResolver(client))))
    }

    @Test fun followsShareRedirectToRoom() = runBlocking {
        val discovery = discovery(mapOf("https://share.example/r" to (302 to "https://www.huya.com/123")))
        assertEquals("https://www.huya.com/123", discovery.resolve("来看直播 https://share.example/r 快来"))
    }

    @Test fun findsRoomAfterMultilineHtml() = runBlocking {
        val html = "<html>\n<head><title>分享</title></head>\n<body><a href='https://www.huya.com/456'>进入直播间</a></body></html>"
        assertEquals("https://www.huya.com/456", discovery(mapOf("https://share.example/r" to (200 to html)))
            .resolve("https://share.example/r"))
    }

    @Test fun inspectsSupportedDomainSharePageWhenRoomParsingFails() = runBlocking {
        val html = "<html><meta property='og:url' content='https://www.huya.com/789'></html>"
        val discovery = discovery(mapOf("https://www.huya.com/share/abc" to (200 to html)))
        assertEquals("https://www.huya.com/share/abc", discovery.resolve("https://www.huya.com/share/abc"))
        assertEquals("https://www.huya.com/789", discovery.resolve("https://www.huya.com/share/abc", inspectSupported = true))
    }

    @Test fun rejectsLoopAndLocalAddress() {
        val loop = discovery(mapOf("https://share.example/r" to (302 to "https://share.example/r")))
        assertThrows(IllegalStateException::class.java) { runBlocking { loop.resolve("https://share.example/r") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { loop.resolve("https://127.0.0.1/r") }
        }
    }
}
