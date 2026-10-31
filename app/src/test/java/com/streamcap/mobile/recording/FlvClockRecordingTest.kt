package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.*
import com.streamcap.mobile.recording.flv.DirectHttpRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class FlvClockRecordingTest {
    @Test fun videoClockEpochChangeInsideOneResponseDoesNotDesynchronizeAudio() = runBlocking {
        val file = Files.createTempFile("flv-clock", ".flv").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockWebServer().use { server ->
                val data = ByteArrayOutputStream().apply {
                    write(byteArrayOf(70, 76, 86, 1, 5, 0, 0, 0, 9, 0, 0, 0, 0))
                    fun tag(type: Int, time: Long, marker: Int) {
                        val body = byteArrayOf(if (type == 9) 0x17 else 0xaf.toByte(), 1, marker.toByte())
                        write(byteArrayOf(type.toByte(), 0, 0, 3, (time shr 16).toByte(), (time shr 8).toByte(),
                            time.toByte(), (time shr 24).toByte(), 0, 0, 0))
                        write(body); write(byteArrayOf(0, 0, 0, 14))
                    }
                    tag(8, 0, 1); tag(9, 0, 2); tag(8, 1000, 3); tag(9, 1000, 4)
                    tag(9, 55_000_000, 5); tag(8, 2000, 6); tag(9, 55_001_000, 7)
                }.toByteArray()
                server.enqueue(MockResponse().setBody(Buffer().write(data)))
                server.enqueue(MockResponse().setResponseCode(404))
                val recorder = DirectHttpRecorder(OkHttpClient(), scope)
                val handle = recorder.start(RecordingRequest(server.url("/live.flv").toString(), emptyMap(), StreamType.FLV, file))
                val terminal = withTimeout(10_000) { recorder.observe(handle).first { it.state == RecorderState.FAILED } }
                assertEquals(2L, terminal.durationSeconds)
                val bytes = file.readBytes()
                val times = (13 until bytes.size step 18).map { offset ->
                    ((bytes[offset + 7].toLong() and 255) shl 24) or
                        ((bytes[offset + 4].toLong() and 255) shl 16) or
                        ((bytes[offset + 5].toLong() and 255) shl 8) or (bytes[offset + 6].toLong() and 255)
                }
                assertEquals(listOf(0L, 0L, 1000L, 1000L, 1001L, 2000L, 2001L), times)
            }
        } finally { scope.cancel(); file.delete() }
    }
}
