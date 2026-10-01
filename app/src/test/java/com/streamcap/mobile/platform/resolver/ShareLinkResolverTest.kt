package com.streamcap.mobile.platform.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ShareLinkResolverTest {
    @Test fun dispatchesShortHostsAndRejectsLookalikes() {
        val client = OkHttpClient()
        val registry = ResolverRegistry(listOf(BilibiliResolver(client), HuyaResolver(client), DouyinResolver(client)))
        assertEquals("bilibili", registry.resolverFor("https://b23.tv/nyVZ9cs").platformId)
        assertEquals("huya", registry.resolverFor("https://hy.fan/CH965Gr").platformId)
        assertEquals("douyin", registry.resolverFor("https://v.douyin.com/RWTjSDkAJRs/").platformId)
        assertFalse(BilibiliResolver(client).supports("https://b23.tv.evil.example/live.bilibili.com"))
        assertFalse(HuyaResolver(client).supports("https://example.com/?next=huya.com"))
    }

    @Test fun expandsRedirectWithoutLoadingRoomPage() = runBlocking {
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requested += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://live.bilibili.com/22508985?share=1")
                .body("".toResponseBody()).build()
        }).build()
        assertEquals("https://live.bilibili.com/22508985?share=1", client.expandRoomShareUrl(
            "https://b23.tv/nyVZ9cs", "b23.tv", listOf("live.bilibili.com"), emptyMap()))
        assertEquals(listOf("https://b23.tv/nyVZ9cs"), requested)
    }
}
