package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.junit.Test

/** Opt-in live preflight. Records failures as evidence without treating them as passes. */
@dagger.hilt.android.testing.HiltAndroidTest
class PlatformPreflightTest : HiltGraphTest() {
    @Test fun inspectLiveCandidates() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val urls = args.getString("preflightUrls").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        require(urls.isNotEmpty()) { "preflightUrls must contain real room URLs" }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val registry = app.testGraph.resolverRegistry
        val client = app.testGraph.httpClient
        urls.chunked(10).forEachIndexed { batch, candidates ->
            candidates.map { url -> async(Dispatchers.IO) {
                try {
                    val resolver = registry.resolverFor(url)
                    val room = withTimeout(30_000) { resolver.resolveRoom(url) }
                    val roomId = app.testGraph.repository.rooms.first().firstOrNull { it.normalizedUrl == url }?.id
                        ?: app.testGraph.repository.addRoom(url)
                    val sources = withTimeout(30_000) { app.testGraph.repository.streams(roomId) }
                    if (sources.isEmpty()) {
                        android.util.Log.w("PlatformPreflight", "RESULT batch=${batch + 1} platform=${resolver.platformId} state=NO_SOURCE url=$url")
                        return@async
                    }
                    val playable = withTimeout(40_000) {
                        val hls = sources.filter { it.type == StreamType.HLS }
                        val flv = sources.filter { it.type == StreamType.FLV }
                        val hlsResult = runCatching { if (hls.isEmpty()) error("no HLS source") else HlsSourceSelector(client).select(hls) }
                        if (hlsResult.isSuccess) hlsResult.getOrThrow() else {
                            flv.firstOrNull { source ->
                                runCatching {
                                    val request = Request.Builder().url(source.url).apply { source.headers.forEach(::header) }.build()
                                    client.newCall(request).execute().use { response ->
                                        check(response.isSuccessful && response.body?.source()?.exhausted() == false)
                                    }
                                }.isSuccess
                            } ?: error("all media routes failed: ${hlsResult.exceptionOrNull()?.message}")
                        }
                    }
                    android.util.Log.i("PlatformPreflight", "RESULT batch=${batch + 1} platform=${resolver.platformId} state=MEDIA_OK type=${playable.type} count=${sources.size} url=$url")
                } catch (failure: Exception) {
                    android.util.Log.w("PlatformPreflight", "RESULT batch=${batch + 1} state=FAILED url=$url reason=${failure.message}")
                }
            } }.awaitAll()
        }
    }
}
