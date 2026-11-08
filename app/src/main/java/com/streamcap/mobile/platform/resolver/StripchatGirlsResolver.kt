package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import okhttp3.OkHttpClient

/** Resolves public Stripchat model pages and their HLS CDN mirrors. */
class StripchatGirlsResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "stripchatgirls"
    override val supportsCredentials = true
    override fun supports(url: String) = url.hasHost("stripchat.com", "stripchatgirls.com")

    override suspend fun resolveRoom(url: String): LiveRoomInfo = resolveRoom(url, null)

    override suspend fun resolveRoom(url: String, credential: PlatformCredential?): LiveRoomInfo {
        val page = load(url, credential)
        return LiveRoomInfo(url, platformId, page.anchor, page.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?): LiveStatus =
        when (val online = load(room.normalizedUrl, credential).online) {
            true -> LiveStatus.LIVE
            false -> LiveStatus.OFFLINE
            null -> LiveStatus.UNKNOWN
        }

    override suspend fun checkRoom(room: LiveRoom, credential: PlatformCredential?): RoomCheckResult {
        val page = load(room.normalizedUrl, credential)
        val status = when (page.online) {
            true -> LiveStatus.LIVE
            false -> LiveStatus.OFFLINE
            null -> LiveStatus.UNKNOWN
        }
        return RoomCheckResult(status, LiveRoomInfo(room.normalizedUrl, platformId, page.anchor, page.title))
    }

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> {
        val page = load(room.normalizedUrl, credential)
        val streamName = page.streamName ?: return emptyList()
        if (page.online != true) return emptyList()
        val headers = page.headers
        return CDN_HOSTS.mapIndexed { index, host ->
            val url = "https://edge-hls.$host/hls/$streamName/master/${streamName}_auto.m3u8"
            StreamSource(url, StreamType.HLS, "线路 ${index + 1}", headers = headers, requiresFfmpeg = true)
        }
    }

    private suspend fun load(url: String, credential: PlatformCredential?): ModelPage {
        val headers = buildMap {
            put("User-Agent", DESKTOP_USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/json")
            put("Origin", "https://stripchatgirls.com")
            put("Referer", "https://stripchatgirls.com/")
            credential?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
        val body = client.getText(url, headers).body
            .replace("\\u002F", "/")
            .replace("\\/", "/")
        val title = TITLE.find(body)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.replace("&amp;", "&")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val streamName = STREAM_NAME.find(body)?.groupValues?.get(1)
            ?.takeIf { it.length <= MAX_STREAM_NAME_LENGTH }
        val online = IS_ONLINE.find(body)?.groupValues?.get(1)?.toBooleanStrictOrNull()
        val anchor = title?.substringBefore(" Webcam Show")?.takeIf(String::isNotBlank)
            ?: streamName
            ?: platformId
        return ModelPage(anchor, title, streamName, online, headers)
    }

    private data class ModelPage(
        val anchor: String,
        val title: String?,
        val streamName: String?,
        val online: Boolean?,
        val headers: Map<String, String>,
    )

    private companion object {
        const val MAX_STREAM_NAME_LENGTH = 128
        val CDN_HOSTS = listOf("doppiocdn.net", "doppiocdn.org", "doppiocdn.live")
        val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val STREAM_NAME = Regex("\"streamName\"\\s*:\\s*\"([A-Za-z0-9_-]{1,$MAX_STREAM_NAME_LENGTH})\"")
        val IS_ONLINE = Regex("\"isOnline\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE)
    }
}
