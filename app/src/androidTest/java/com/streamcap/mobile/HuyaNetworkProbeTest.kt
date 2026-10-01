package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: checks CDN reuse without modifying rooms or recording history. */
class HuyaNetworkProbeTest {
    @Test fun selectedPlaylistCanBeReadRepeatedly() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("probeRoomId")?.toLongOrNull()
        assumeTrue("Requires explicit room ID", id != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val sources = app.container.repository.streams(id!!).filter { it.type == StreamType.HLS }
        val probeClient = app.container.httpClient
        val selected = HlsSourceSelector(probeClient).select(sources)
        android.util.Log.i("HuyaProbe", "selected matches original=${sources.any { it.url == selected.url }}")
        repeat(3) { index ->
            val request = Request.Builder().url(selected.url).apply { selected.headers.forEach(::header) }.build()
            probeClient.newCall(request).execute().use { response ->
                android.util.Log.i("HuyaProbe", "read=$index status=${response.code} host=${response.request.url.host} path=${response.request.url.encodedPath}")
                assertTrue("Selected playlist rejected on read $index: HTTP ${response.code}", response.isSuccessful)
                assertTrue(response.body!!.string().startsWith("#EXTM3U"))
            }
        }
    }
}
