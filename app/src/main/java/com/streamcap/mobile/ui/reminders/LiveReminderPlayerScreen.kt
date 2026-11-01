package com.streamcap.mobile.recording.hls

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.platform.resolver.secureStreamUrl
import com.streamcap.mobile.platform.resolver.selectQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class HlsSourceSelection(val sourceIndex: Int, val source: StreamSource)

class HlsSourceSelector(client: OkHttpClient, private val selectionTimeoutMillis: Long = 30_000L) {
    private val client = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun select(sources: List<StreamSource>, quality: String = "原画", previousHost: String? = null): StreamSource =
        selectWithIndex(sources, quality, previousHost).source

    suspend fun selectWithIndex(
        sources: List<StreamSource>,
        quality: String = "原画",
        previousHost: String? = null,
    ): HlsSourceSelection =
        withTimeoutOrNull(selectionTimeoutMillis) {
            // A stalled CDN may still return playlist/segment headers successfully.
            // Try another CDN before reusing it when the recorder requests a refresh.
            val indexed = sources.withIndex().toList()
            val ordered = if (previousHost == null) indexed else indexed.sortedBy {
                    it.value.url.toHttpUrlOrNull()?.host.equals(previousHost, ignoreCase = true)
            }
            selectWithinDeadline(ordered, quality, client.newBuilder().addNetworkInterceptor(HlsSessionCookies()).build())
        }
            ?: error("直播线路探测超时，请稍后重试")

    private suspend fun selectWithinDeadline(
        sources: List<IndexedValue<StreamSource>>,
        quality: String,
        client: OkHttpClient,
    ): HlsSourceSelection = withContext(Dispatchers.IO) {
        val failures = linkedSetOf<String>()
        for ((sourceIndex, source) in sources) {
            coroutineContext.ensureActive()
            try {
                var url = secureStreamUrl(source.url)
                var selectedSource = source
                repeat(4) {
                    val request = Request.Builder().url(url).apply { source.headers.forEach(::header) }.build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "播放列表 HTTP ${response.code}" }
                        val body = response.body!!.string()
                        check(body.trimStart().startsWith("#EXTM3U")) { "响应不是 HLS 播放列表" }
                        if (!body.contains("#EXT-X-STREAM-INF")) {
                            check(body.contains("#EXTINF:"))
                            val segment = body.lines().map(String::trim).last { it.isNotEmpty() && !it.startsWith('#') }
                            val segmentUrl = secureStreamUrl(
                                response.request.url.resolve(segment)?.toString() ?: error("Invalid segment URL"),
                            )
                            val probe = Request.Builder().url(segmentUrl).apply { source.headers.forEach(::header) }.build()
                            client.newCall(probe).execute().use { media ->
                                check(media.isSuccessful) { "Media segment HTTP ${media.code}" }
                                check(media.body?.source()?.exhausted() == false) { "Empty media segment" }
                            }
                            return@withContext HlsSourceSelection(
                                sourceIndex,
                                selectedSource.copy(url = if (source.separateAudio) source.url
                                    else secureStreamUrl(response.request.url.toString())),
                            )
                        }
                        val lines = body.lines().map(String::trim)
                        val variants = lines.mapIndexedNotNull { index, line ->
                            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                            val child = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                                ?: return@mapIndexedNotNull null
                            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            val label = when {
                                height == null -> ""
                                height >= 2160 -> "4k"
                                height >= 1080 -> "1080p"
                                height >= 720 -> "720p"
                                height >= 480 -> "480p"
                                height >= 360 -> "360p"
                                else -> "流畅"
                            }
                            source.copy(url = response.request.url.resolve(child)!!.toString(), quality = label, bitrate = bandwidth)
                        }
                        selectedSource = variants.selectQuality(quality).first()
                        url = secureStreamUrl(selectedSource.url)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += when (failure) {
                    is java.io.InterruptedIOException -> "线路请求超时"
                    is java.io.IOException -> "线路网络请求失败"
                    else -> failure.message ?: "线路格式无效"
                }
                System.err.println("StreamCap source ${java.net.URI(source.url).host}: ${failure.message}")
                // A rejected CDN must not hide working alternatives.
            }
        }
        error("所有直播线路均不可用：${failures.joinToString("；").ifBlank { "未取得 HLS 线路" }}")
    }
}
