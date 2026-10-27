package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.*
import com.streamcap.mobile.recording.hls.HlsRecorder
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HlsCookieSessionTest {
    @Test fun cookieSessionsRespectHostPathExpiryAndExplicitCredentials() {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().addNetworkInterceptor(com.streamcap.mobile.recording.hls.HlsSessionCookies()).build()
            server.enqueue(MockResponse().addHeader("Set-Cookie", "session=cdn; Path=/media"))
            server.enqueue(MockResponse())
            server.enqueue(MockResponse())
            server.enqueue(MockResponse().addHeader("Set-Cookie", "session=gone; Max-Age=0; Path=/media"))
            server.enqueue(MockResponse())
            fun get(path: String, account: Boolean = false) {
                client.newCall(okhttp3.Request.Builder().url(server.url(path)).apply {
                    if (account) header("Cookie", "session=account; other=kept")
                }.build()).execute().close()
            }
            get("/media/start")
            get("/other")
            get("/media/next", true)
            get("/media/end")
            get("/media/after")
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("session=account; other=kept", server.takeRequest().getHeader("Cookie"))
            assertEquals("session=cdn", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun playlistSessionCookieAuthorizesProbeAndActualRecording() = runBlocking {
        val directory = Files.createTempDirectory("hls-cookie").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.path == "/live.m3u8") return MockResponse()
                            .addHeader("Set-Cookie", "cdn_session=valid; Path=/")
                            .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MAP:URI=\"/init\"\n#EXTINF:1,\n/media\n#EXT-X-ENDLIST\n")
                        if (request.getHeader("Cookie")?.contains("cdn_session=valid") != true)
                            return MockResponse().setResponseCode(401)
                        return MockResponse().setBody(if (request.path == "/init") "INIT" else "MEDIA")
                    }
                }
                val source = StreamSource(server.url("/live.m3u8").toString(), StreamType.HLS, "原画")
                val selected = HlsSourceSelector(OkHttpClient()).select(listOf(source))
                val recorder = HlsRecorder(OkHttpClient(), scope)
                val handle = recorder.start(RecordingRequest(selected.url, selected.headers, StreamType.HLS,
                    java.io.File(directory, "capture.ts")))
                val result = withTimeout(10_000) { recorder.observe(handle).first {
                    it.state == RecorderState.COMPLETED || it.state == RecorderState.FAILED
                } }
                assertEquals(RecorderState.COMPLETED, result.state)
                assertEquals("INITMEDIA", result.outputFiles.single().readText())
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
}
