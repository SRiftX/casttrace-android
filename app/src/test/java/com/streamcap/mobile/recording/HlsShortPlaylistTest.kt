package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.*
import com.streamcap.mobile.recording.hls.HlsRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class HlsShortPlaylistTest {
    @Test fun threeSegmentLiveWindowStartsAtCompletedEdgeWithoutOldBacklog() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/live.m3u8") {
                MockResponse().setBody("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:10\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nold.ts\n#EXTINF:4,\nready.ts\n#EXTINF:4,\nnewest.ts\n")
            } else MockResponse().setBody(request.path.orEmpty())
        }
        server.start()
        val directory = Files.createTempDirectory("hls-short-window").toFile()
        val file = java.io.File(directory, "capture.ts")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val recorder = HlsRecorder(OkHttpClient(), scope)
            val handle = recorder.start(RecordingRequest(server.url("/live.m3u8").toString(), emptyMap(), StreamType.HLS, file))
            withTimeout(10_000) { recorder.observe(handle).first { it.bytesWritten > 0 } }
            recorder.stop(handle, StopReason.USER)
            withTimeout(10_000) { recorder.observe(handle).first { it.state == RecorderState.STOPPED } }
            assertEquals("/ready.ts", file.readText())
        } finally {
            scope.cancel()
            server.shutdown()
            directory.deleteRecursively()
        }
    }
}
