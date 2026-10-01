package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.StreamType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectStreamResolverTest {
    private val resolver = DirectStreamResolver(OkHttpClient())

    @Test
    fun supportsHlsAndFlvUrlsWithQueryParameters() {
        assertTrue(resolver.supports("https://cdn.example.com/live/master.m3u8?token=abc"))
        assertTrue(resolver.supports("https://cdn.example.com/live/stream.flv?token=abc"))
    }

    @Test
    fun rejectsOrdinaryWebPages() {
        assertFalse(resolver.supports("https://example.com/live/123"))
        assertFalse(resolver.supports("not-a-url"))
    }

    @Test
    fun resolvesDirectStreamMetadata() = runTest {
        val room = resolver.resolveRoom("https://cdn.example.com/channel/demo.flv#preview")

        assertEquals("direct", resolver.platformId)
        assertEquals("直链 FLV", room.platform)
        assertEquals("demo", room.anchorName)
        assertEquals("https://cdn.example.com/channel/demo.flv", room.normalizedUrl)
    }

    @Test
    fun rejectsCleartextDirectStream() = runTest {
        val error = runCatching {
            resolver.resolveRoom("http://cdn.example.com/channel/demo.flv")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
