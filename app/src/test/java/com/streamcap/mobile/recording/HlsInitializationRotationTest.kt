package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.*
import com.streamcap.mobile.recording.hls.HlsRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class HlsInitializationRotationTest {
    @Test fun livePlaylistWaitsForNewestSegmentToBeCommittedAndEndlistDrainsIt() = runBlocking {
        val directory = Files.createTempDirectory("hls-edge-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                val polls = AtomicInteger()
                var prematureRequest = false
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/live.m3u8") {
                            val ended = polls.incrementAndGet() > 2
                            return MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n" +
                                (1..6).joinToString("") { "#EXTINF:1,\n/$it.ts\n" } + if (ended) "#EXT-X-ENDLIST\n" else "")
                        }
                        if (request.path == "/6.ts" && polls.get() <= 2) {
                            prematureRequest = true
                            return MockResponse().setResponseCode(503)
                        }
                        return MockResponse().setBody(if (request.path == "/5.ts") "FIVE" else "SIX")
                    }
                }
                val recorder = HlsRecorder(OkHttpClient(), scope)
                val handle = recorder.start(RecordingRequest(server.url("/live.m3u8").toString(), emptyMap(), StreamType.HLS,
                    java.io.File(directory, "capture.ts")))
                val result = withTimeout(10_000) { recorder.observe(handle).first { it.state == RecorderState.COMPLETED || it.state == RecorderState.FAILED } }
                assertEquals(RecorderState.COMPLETED, result.state)
                assertFalse(prematureRequest)
                assertEquals("FIVESIX", result.outputFiles.single().readText())
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun changedFmp4InitializationStartsANewOutputInsteadOfDiscardingEarlierMedia() = runBlocking {
        val directory = Files.createTempDirectory("hls-init-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                val playlists = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        val body = when {
                            path == "/live.m3u8" -> if (playlists.incrementAndGet() <= 2)
                                "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:1\n#EXT-X-MAP:URI=\"/init-a\"\n#EXTINF:1,\n/one\n"
                            else "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:2\n#EXT-X-MAP:URI=\"/init-b\"\n#EXTINF:1,\n/two\n#EXT-X-ENDLIST\n"
                            path == "/init-a" -> "INIT_A"
                            path == "/init-b" -> "INIT_B"
                            path == "/one" -> "MEDIA_ONE"
                            else -> "MEDIA_TWO"
                        }
                        return MockResponse().setBody(body)
                    }
                }
                val recorder = HlsRecorder(OkHttpClient(), scope)
                val handle = recorder.start(RecordingRequest(server.url("/live.m3u8").toString(), emptyMap(), StreamType.HLS,
                    java.io.File(directory, "capture.ts")))
                val result = withTimeout(10_000) { recorder.observe(handle).first { it.state == RecorderState.COMPLETED || it.state == RecorderState.FAILED } }
                assertEquals(RecorderState.COMPLETED, result.state)
                assertEquals(2, result.outputFiles.size)
                assertEquals("INIT_AMEDIA_ONE", result.outputFiles[0].readText())
                assertEquals("INIT_BMEDIA_TWO", result.outputFiles[1].readText())
                assertEquals(listOf(result.outputFiles[0]), result.completedOutputFiles)
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
