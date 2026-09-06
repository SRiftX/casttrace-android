package com.streamcap.mobile.platform.resolver

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.PlatformCredential
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.random.Random

class HuyaResolver(private val client: OkHttpClient) : PlatformResolver {
    override val platformId = "huya"

    override fun supports(url: String) = url.contains("huya.com", ignoreCase = true)

    override suspend fun resolveRoom(url: String): LiveRoomInfo {
        val data = load(url)
        return LiveRoomInfo(data.liveUrl, "虎牙直播", data.anchorName, data.title)
    }

    override suspend fun checkLive(room: LiveRoom, credential: PlatformCredential?) =
        if (load(room.normalizedUrl).live) LiveStatus.LIVE else LiveStatus.OFFLINE

    override suspend fun getStreams(room: LiveRoom, credential: PlatformCredential?): List<StreamSource> {
        val data = load(room.normalizedUrl)
        if (!data.live) return emptyList()
        val ratio = when (room.quality.qualityCode()) {
            "BD" -> "4000"; "UHD" -> "2000"; "HD" -> "1000"; "SD" -> "800"; "LD" -> "500"; else -> null
        }
        return data.sources.map { source ->
            android.util.Log.i("StreamCapSource", "Huya candidate ${source.type} ${java.net.URI(source.url).host}")
            source.copy(url = withRatio(source.url, ratio.orEmpty()), quality = room.quality)
        }
    }

    private suspend fun load(input: String): HuyaData {
        var roomId = input.substringBefore('?').trimEnd('/').substringAfterLast('/')
        require(roomId.isNotBlank()) { "虎牙直播间地址缺少房间号" }
        if (roomId.any(Char::isLetter)) {
            val html = client.getText(input, MOBILE_PAGE_HEADERS).body
            roomId = Regex("\\\"ProfileRoom\\\"\\s*:\\s*(\\d+)")
                .find(html)?.groupValues?.get(1)
                ?: Regex("profileRoom[^0-9]{0,20}(\\d+)", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)
                ?: throw IllegalArgumentException("无法取得虎牙房间号，请使用完整直播间地址")
        }
        val api = httpUrl(
            "https://mp.huya.com/cache.php",
            mapOf("m" to "Live", "do" to "profileRoom", "roomid" to roomId, "showSecret" to "1"),
        )
        val root = JSONObject(client.getText(api, DEFAULT_HEADERS).body)
        val data = root.optJSONObject("data") ?: error(root.optString("message", "虎牙接口没有返回房间数据"))
        val profile = data.optJSONObject("profileInfo")
        val liveData = data.optJSONObject("liveData")
        val live = data.optString("realLiveStatus") == "ON"
        val liveUrl = "https://www.huya.com/$roomId"
        val sources = mutableListOf<StreamSource>()
        if (live) {
            // Mobile CDN signatures can be rejected across game categories, not just LOL.
            sources += runCatching { loadWebSources(liveUrl) }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    android.util.Log.w("StreamCapSource", "Huya web resolution failed: ${it.message}")
                }.getOrDefault(emptyList())
            val stream = data.optJSONObject("stream")
            val list = stream?.optJSONArray("baseSteamInfoList")
            val items = if (list == null) emptyList() else (0 until list.length())
                .mapNotNull(list::optJSONObject)
                .sortedBy { item ->
                    when (item.optString("sCdnType")) {
                        "TX" -> 0
                        "HW" -> 1
                        else -> 2
                    }
                }
            for (item in items) {
                val name = item.optString("sStreamName")
                val cdn = item.optString("sCdnType")
                // 移动接口已经分别返回可用的 FLV/HLS 签名，不能把 FLV 签名重算后复用于 HLS。
                val flvAntiCode = item.optString("sFlvAntiCode")
                val hlsAntiCode = item.optString("sHlsAntiCode").ifBlank { flvAntiCode }
                val flvSuffix = item.optString("sFlvUrlSuffix").ifBlank { "flv" }
                val hlsSuffix = item.optString("sHlsUrlSuffix").ifBlank { "m3u8" }
                var flv = "${item.optString("sFlvUrl")}/$name.$flvSuffix?$flvAntiCode"
                var hls = "${item.optString("sHlsUrl")}/$name.$hlsSuffix?$hlsAntiCode"
                if (cdn == "TX" || cdn == "HW") {
                    flv = flv.replace("&ctype=tars_mp", "&ctype=huya_webh5").replace("&fs=bhct", "&fs=bgct")
                    hls = hls.replace("&ctype=tars_mp", "&ctype=huya_webh5").replace("&fs=bhct", "&fs=bgct")
                }
                if (flv.startsWith("http")) sources += StreamSource(flv, StreamType.FLV, "原画", headers = STREAM_HEADERS)
                if (hls.startsWith("http")) sources += StreamSource(hls, StreamType.HLS, "原画", headers = STREAM_HEADERS)
            }
        }
        return HuyaData(
            liveUrl = liveUrl,
            anchorName = profile?.optString("nick").orEmpty().ifBlank { "虎牙主播 $roomId" },
            title = liveData?.optString("introduction")?.takeIf(String::isNotBlank),
            live = live,
            sources = sources,
        )
    }

    private suspend fun loadWebSources(liveUrl: String): List<StreamSource> {
        val html = client.getText(liveUrl, DEFAULT_HEADERS).body
        val encoded = Regex(
            "stream:\\s*(\\{\\\"data\\\".*?),\\\"iWebDefaultBitRate\\\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(html)?.groupValues?.get(1) ?: error("虎牙网页没有返回直播线路")
        val first = JSONObject("$encoded}").optJSONArray("data")?.optJSONObject(0)
            ?: error("虎牙网页直播线路格式异常")
        val list = first.optJSONArray("gameStreamInfoList") ?: return emptyList()
        // Preserve the website's CDN priority, as streamget does. Forcing TX/HW
        // ahead of the supplied first CDN can select a forbidden HLS endpoint.
        val items = (0 until list.length()).mapNotNull(list::optJSONObject)
        return buildList {
            items.forEach { item ->
                val name = item.optString("sStreamName")
                if (name.isBlank()) return@forEach
                val flvAntiCode = refreshAntiCode(item.optString("sFlvAntiCode"), name)
                val hlsAntiCode = refreshAntiCode(item.optString("sHlsAntiCode"), name)
                val flv = "${item.optString("sFlvUrl")}/$name.${item.optString("sFlvUrlSuffix", "flv")}?$flvAntiCode"
                val hls = "${item.optString("sHlsUrl")}/$name.${item.optString("sHlsUrlSuffix", "m3u8")}?$hlsAntiCode"
                if (flv.startsWith("http")) add(StreamSource(flv, StreamType.FLV, "原画", headers = STREAM_HEADERS))
                if (hls.startsWith("http")) add(StreamSource(hls, StreamType.HLS, "原画", headers = STREAM_HEADERS))
            }
        }.distinctBy { it.url }
    }

    private fun refreshAntiCode(old: String, streamName: String): String {
        if (old.isBlank()) return old
        val query = old.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) null else part.substring(0, index) to
                URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8.name())
        }.toMap()
        val fm = query["fm"] ?: return old
        val secretPrefix = runCatching {
            val encoded = fm.replace(' ', '+')
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { Base64.getUrlDecoder().decode(encoded) }
            String(bytes, StandardCharsets.UTF_8).substringBefore('_')
        }.getOrNull() ?: return old
        val ctype = query["ctype"] ?: "tars_mp"
        val fs = query["fs"] ?: "bgct"
        val now = System.currentTimeMillis()
        val sdkSid = now / 1_000L * 1_000L
        val uid = Random.nextLong(1_400_000_000_000L, 1_400_010_000_000L)
        val seqId = uid + sdkSid
        val uuid = ((sdkSid % 10_000_000_000L) * 1_000L + Random.nextInt(1_000)) % 4_294_967_295L
        val wsTime = ((sdkSid + 110_624L) / 1_000L).toString(16)
        val secretHash = "$seqId|$ctype|100".md5()
        val wsSecret = "${secretPrefix}_${uid}_${streamName}_${secretHash}_${wsTime}".md5()
        return "wsSecret=$wsSecret&wsTime=$wsTime&seqid=$seqId&ctype=$ctype&ver=1" +
            "&fs=$fs&uuid=$uuid&u=$uid&t=100&sv=2403051612&sdk_sid=$sdkSid&codec=264"
    }

    private data class HuyaData(
        val liveUrl: String,
        val anchorName: String,
        val title: String?,
        val live: Boolean,
        val sources: List<StreamSource>,
    )

    private fun withRatio(url: String, ratio: String): String = if (Regex("[?&]ratio=").containsMatchIn(url)) {
        url.replace(Regex("([?&]ratio=)[^&]*"), "$1$ratio")
    } else {
        "$url&ratio=$ratio"
    }

    companion object {
        private val DEFAULT_HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://www.huya.com/")
        private val STREAM_HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT, "Referer" to "https://www.huya.com/")
        private val MOBILE_PAGE_HEADERS = mapOf(
            "User-Agent" to "ios/7.830 (ios 17.0; ; iPhone 15 (A2846/A3089/A3090/A3092))",
            "Xweb-Xhr" to "1",
            "Referer" to "https://servicewechat.com/wx74767bf0b684f7d3/301/page-frame.html",
            "Accept-Language" to "zh-CN,zh;q=0.9",
        )
    }
}
