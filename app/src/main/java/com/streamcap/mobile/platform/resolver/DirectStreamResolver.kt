package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class DirectStreamResolver(
    private val client: OkHttpClient,
) : PlatformResolver {
    override val platformId = "direct"

    override fun supports(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return false
        return path.endsWith(".m3u8") || path.endsWith(".flv")
    }

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val parsed = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 地址")
        if (!supports(url)) {
            throw UnsupportedPlatformException(url)
        }
        val type = streamType(url)
        val fileName = parsed.pathSegments.lastOrNull().orEmpty().substringBeforeLast('.')
        return LiveRoomInfo(
            normalizedUrl = parsed.newBuilder().fragment(null).build().toString(),
            platform = "直链 ${type.name}",
            anchorName = fileName.ifBlank { "未命名直播流" },
            roomTitle = "直接直播源",
        )
    }

    override suspend fun checkLive(
        room: LiveRoom,
        credential: PlatformCredential?,
    ): LiveStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(room.originalUrl)
            .head()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) LiveStatus.LIVE else LiveStatus.OFFLINE
            }
        }.getOrElse { LiveStatus.ERROR }
    }

    override suspend fun getStreams(
        room: LiveRoom,
        credential: PlatformCredential?,
    ): List<StreamSource> = listOf(
        StreamSource(
            url = room.originalUrl,
            type = streamType(room.originalUrl),
            quality = room.quality,
            requiresFfmpeg = streamType(room.originalUrl) == StreamType.HLS,
        ),
    )

    private fun streamType(url: String): StreamType = when {
        url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.endsWith(".m3u8") == true -> StreamType.HLS
        url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.endsWith(".flv") == true -> StreamType.FLV
        else -> StreamType.UNKNOWN
    }
}
