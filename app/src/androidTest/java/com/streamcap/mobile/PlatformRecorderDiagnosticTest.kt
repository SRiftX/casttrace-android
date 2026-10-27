package com.streamcap.mobile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.*
import com.streamcap.mobile.recording.hls.HlsRecorder
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import okhttp3.ResponseBody.Companion.toResponseBody

/** Isolates transport failures from service/muxer behavior; explicit live URL required. */
class PlatformRecorderDiagnosticTest {
    @Test fun inspectHlsTransport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val url = requireNotNull(InstrumentationRegistry.getArguments().getString("transportUrl"))
        val app = instrumentation.targetContext.applicationContext as StreamCapApplication
        val client = app.container.httpClient.newBuilder().apply {
            if (InstrumentationRegistry.getArguments().getString("transportProtocol") == "http1")
                protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            if (InstrumentationRegistry.getArguments().getString("transportHoldback") == "1") addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!chain.request().url.encodedPath.endsWith(".m3u8")) response else {
                    val body = response.body!!.string()
                    val lines = body.lines().toMutableList()
                    val lastMedia = lines.indexOfLast { it.isNotBlank() && !it.startsWith('#') }
                    val lastInf = lines.indexOfLast { it.startsWith("#EXTINF:") }
                    if (!body.contains("#EXT-X-STREAM-INF") && !body.contains("#EXT-X-ENDLIST") &&
                        lines.count { it.startsWith("#EXTINF:") } > 3 && lastMedia > lastInf) {
                        lines.removeAt(lastMedia); lines.removeAt(lastInf)
                    }
                    response.newBuilder().body(lines.joinToString("\n").toResponseBody()).build()
                }
            }
        }.eventListenerFactory {
            object : okhttp3.EventListener() {
                override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.i("PlatformTransport", "HTTP code=${response.code} protocol=${response.protocol} length=${response.body?.contentLength()} host=${response.request.url.host} path=${response.request.url.encodedPath}")
                }
                override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                    Log.i("PlatformTransport", "HTTP_FAILED type=${ioe.javaClass.simpleName} host=${call.request().url.host}")
                }
                override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                    Log.i("PlatformTransport", "BODY bytes=$byteCount host=${call.request().url.host}")
                }
            }
        }.build()
        val resolver = app.container.resolverRegistry.resolverFor(url)
        val info = resolver.resolveRoom(url)
        val roomId = app.container.repository.rooms.first().firstOrNull { it.normalizedUrl == info.normalizedUrl }?.id
            ?: app.container.repository.addRoom(info.normalizedUrl)
        val sources = app.container.repository.streams(roomId).filter { it.type == StreamType.HLS }
        val source = HlsSourceSelector(client).select(sources)
        Log.i("PlatformTransport", "SELECTED host=${java.net.URI(source.url).host} path=${java.net.URI(source.url).path}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(instrumentation.targetContext.cacheDir, "transport-${System.currentTimeMillis()}.ts")
        val recorder = HlsRecorder(client, scope)
        val handle = recorder.start(RecordingRequest(source.url, source.headers, source.type, file))
        var bytes = 0L
        val observer = launch { recorder.observe(handle).collect {
            bytes = maxOf(bytes, it.bytesWritten)
            Log.i("PlatformTransport", "PROGRESS state=${it.state} bytes=${it.bytesWritten} seconds=${it.durationSeconds} error=${it.errorMessage}")
        } }
        val seconds = InstrumentationRegistry.getArguments().getString("transportSeconds")?.toLongOrNull()?.coerceIn(15, 180) ?: 45L
        try { delay(seconds * 1000) } finally {
            recorder.stop(handle, StopReason.USER)
            observer.cancelAndJoin()
            scope.cancel()
        }
        Log.i("PlatformTransport", "RESULT bytes=$bytes fileBytes=${file.length()}")
        assertTrue("No HLS media received; inspect PlatformTransport log", bytes > 0)
    }
}
