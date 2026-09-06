package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HlsSourceSelectorTest {
    @Test fun reportsForbiddenPlaylistInsteadOfGenericRetryError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            try {
                HlsSourceSelector(OkHttpClient()).select(listOf(
                    StreamSource(server.url("live.m3u8").toString(), StreamType.HLS, "original"),
                ))
                fail("Expected unavailable source")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("HTTP 403"))
            }
        }
    }

    @Test fun skipsForbiddenCdnAndResolvesRelativeMaster() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nmedia.m3u8\n"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4,\n1.ts\n"))
            server.enqueue(MockResponse().setBody("media"))
            val sources = listOf("bad.m3u8", "path/master.m3u8").map {
                StreamSource(server.url(it).toString(), StreamType.HLS, "original", headers = mapOf("Referer" to "https://example.com/"))
            }
            val selected = HlsSourceSelector(OkHttpClient()).select(sources)
            assertEquals(server.url("path/media.m3u8").toString(), selected.url)
            repeat(4) { assertEquals("https://example.com/", server.takeRequest().getHeader("Referer")) }
        }
    }
}
