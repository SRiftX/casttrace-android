package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.*
import okhttp3.OkHttpClient

/** Fallback adapter for catalog platforms whose page exposes a public media URL. */
class GenericWebResolver(
    override val platformId: String,
    private val hosts: List<String>,
    private val client: OkHttpClient,
) : PlatformResolver {
    override fun supports(url: String) = url.hasHost(*hosts.toTypedArray())

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val result = load(url)
        return LiveRoomInfo(url, platformId, result.anchor, result.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl, credential).sources.isEmpty()) LiveStatus.UNKNOWN else LiveStatus.LIVE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?) =
        load(room.normalizedUrl, credential).sources.selectQuality(room.quality)

    private suspend fun load(url: String, credential: PlatformCredential? = null): Result {
        val headers = buildMap {
            put("User-Agent", DESKTOP_USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/json")
            credential?.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
        val body = client.getText(url, headers).body
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()?.takeIf { it.isNotBlank() }
        val media = Regex("https://[^\\\"'<>\\s]+(?:\\.m3u8|\\.flv)(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.value.replace("\\u0026", "&") }.distinct().take(12).toList()
        val sources = media.map { address ->
            val type = if (address.contains(".flv", true)) StreamType.FLV else StreamType.HLS
            StreamSource(address, type, "原画", headers = headers, requiresFfmpeg = type == StreamType.HLS)
        }
        val anchor = Regex("(?:主播|anchor|nickname|userName)[^\\\"']*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: platformId
        return Result(anchor, title, sources)
    }

    private data class Result(val anchor: String, val title: String?, val sources: List<StreamSource>)
}
