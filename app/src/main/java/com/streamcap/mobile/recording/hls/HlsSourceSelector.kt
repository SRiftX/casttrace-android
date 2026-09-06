package com.streamcap.mobile.recording.hls

import com.streamcap.mobile.domain.model.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class HlsSourceSelector(client: OkHttpClient) {
    private val client = client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()

    suspend fun select(sources: List<StreamSource>): StreamSource = withContext(Dispatchers.IO) {
        val failures = linkedSetOf<String>()
        for (source in sources) {
            coroutineContext.ensureActive()
            try {
                var url = source.url
                repeat(4) {
                    val request = Request.Builder().url(url).apply { source.headers.forEach(::header) }.build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "播放列表 HTTP ${response.code}" }
                        val body = response.body!!.string()
                        check(body.trimStart().startsWith("#EXTM3U")) { "响应不是 HLS 播放列表" }
                        if (!body.contains("#EXT-X-STREAM-INF")) {
                            check(body.contains("#EXTINF:"))
                            val segment = body.lines().map(String::trim).last { it.isNotEmpty() && !it.startsWith('#') }
                            val segmentUrl = response.request.url.resolve(segment) ?: error("Invalid segment URL")
                            val probe = Request.Builder().url(segmentUrl).apply { source.headers.forEach(::header) }.build()
                            client.newCall(probe).execute().use { media ->
                                check(media.isSuccessful) { "Media segment HTTP ${media.code}" }
                                check(media.body?.source()?.exhausted() == false) { "Empty media segment" }
                            }
                            return@withContext source.copy(url = response.request.url.toString())
                        }
                        val lines = body.lines().map(String::trim)
                        val variants = lines.mapIndexedNotNull { index, line ->
                            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
                            val child = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                                ?: return@mapIndexedNotNull null
                            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                            bandwidth to child
                        }
                        url = response.request.url.resolve(variants.maxBy { it.first }.second)!!.toString()
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
