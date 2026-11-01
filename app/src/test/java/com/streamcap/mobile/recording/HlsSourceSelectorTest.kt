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
    @Test fun externalAudioSourcePreservesMasterAfterVideoProbe() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:BANDWIDTH=500000,AUDIO=\"audio\"\nvideo.m3u8\n"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:2,\none.ts\n"))
            server.enqueue(MockResponse().setBody("media"))
            val source = StreamSource(server.url("/master.m3u8").toString(), StreamType.HLS, "流畅", separateAudio = true)
            val selected = HlsSourceSelector(OkHttpClient()).select(listOf(source), "流畅")
            assertEquals(source.url, selected.url)
            assertTrue(selected.separateAudio)
        }
    }
    @Test fun refreshTriesAnotherCdnEvenWhenPreviousCdnStillPassesHeaderProbe() = runBlocking {
        MockWebServer().use { previous ->
            MockWebServer().use { alternate ->
                previous.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:2,\none.ts\n"))
                previous.enqueue(MockResponse().setBody("old"))
                alternate.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:2,\none.ts\n"))
                alternate.enqueue(MockResponse().setBody("new"))
                val oldUrl = previous.url("/old.m3u8").newBuilder().host("localhost").build()
                val newUrl = alternate.url("/new.m3u8").newBuilder().host("127.0.0.1").build()
                val sources = listOf(oldUrl, newUrl).map { StreamSource(it.toString(), StreamType.HLS, "原画") }
                val selected = HlsSourceSelector(OkHttpClient()).select(sources, previousHost = "localhost")
                assertEquals(newUrl.toString(), selected.url)
                assertEquals(0, previous.requestCount)
                assertEquals(2, alternate.requestCount)
            }
        }
    }

    @Test fun smoothQualityDoesNotSilentlySelectHighestBandwidthHlsVariant() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080\nhigh.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=192000,RESOLUTION=256x144\nlow.m3u8\n"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:4,\n1.ts\n"))
            server.enqueue(MockResponse().setBody("media"))
            val source = StreamSource(server.url("/master.m3u8").toString(), StreamType.HLS, "原画")
            val selected = HlsSourceSelector(OkHttpClient()).select(listOf(source), "流畅")
            assertEquals(server.url("/low.m3u8").toString(), selected.url)
            server.takeRequest()
            assertEquals("/low.m3u8", server.takeRequest().path)
        }
    }

    @Test fun totalDeadlineDoesNotProbeEverySlowCandidate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setHeadersDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            val sources = (1..20).map {
                StreamSource(server.url("/$it.m3u8").toString(), StreamType.HLS, "original")
            }
            try {
                HlsSourceSelector(OkHttpClient(), selectionTimeoutMillis = 50).select(sources)
                fail("Expected selection deadline")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("探测超时"))
                assertEquals(1, server.requestCount)
            }
        }
    }

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
            val selection = HlsSourceSelector(OkHttpClient()).selectWithIndex(sources)
            assertEquals(1, selection.sourceIndex)
            assertEquals(server.url("path/media.m3u8").toString(), selection.source.url)
            repeat(4) { assertEquals("https://example.com/", server.takeRequest().getHeader("Referer")) }
        }
    }
}
