package com.streamcap.mobile.danmaku

import com.streamcap.mobile.platform.resolver.DESKTOP_USER_AGENT
import com.streamcap.mobile.platform.resolver.getText
import com.streamcap.mobile.platform.resolver.postForm
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** Receive-only public event subscription used by the web player. */
class TwitCastingDanmakuClient(client: OkHttpClient) : WebSocketChatClient(client) {
    override suspend fun prepare(context: DanmakuConnectContext): ChatConnection {
        val room = requireNotNull(context.roomUrl.toHttpUrlOrNull())
        require(room.scheme == "https" && (room.host == "twitcasting.tv" || room.host.endsWith(".twitcasting.tv")))
        val headers = buildMap {
            put("User-Agent", DESKTOP_USER_AGENT)
            put("Referer", "https://twitcasting.tv/")
            context.cookie?.takeIf(String::isNotBlank)?.let { put("Cookie", it) }
        }
        val page = Jsoup.parse(httpClient.getText(context.roomUrl, headers).body)
        check(page.selectFirst("[data-is-onlive]")?.attr("data-is-onlive") == "true") { "TwitCasting 直播已结束" }
        val movie = page.selectFirst("[data-movie-id]")?.attr("data-movie-id").orEmpty()
        require(movie.matches(Regex("[0-9]{1,30}"))) { "TwitCasting 缺少当前直播 ID" }
        val endpoint = JSONObject(httpClient.postForm("https://twitcasting.tv/eventpubsuburl.php",
            mapOf("movie_id" to movie), headers)).getString("url")
        val parsed = requireNotNull(endpoint.replaceFirst("wss://", "https://").toHttpUrlOrNull())
        require(endpoint.startsWith("wss://") && parsed.host.endsWith(".twitcasting.tv")) { "TwitCasting 聊天地址无效" }
        return object : ChatConnection {
            override val request = Request.Builder().url(endpoint).header("Origin", "https://twitcasting.tv").build()
            override fun text(socket: okhttp3.WebSocket, text: String, emit: (DanmakuFrame) -> Unit) {
                val events = JSONArray(text)
                emit(DanmakuFrame.Status("subscribed-real"))
                for (i in 0 until events.length()) {
                    events.optJSONObject(i)?.let { parseChat(it, context, android.os.SystemClock.elapsedRealtime()) }?.let(emit)
                }
            }
        }
    }

    companion object {
        internal fun parseChat(item: JSONObject, context: DanmakuConnectContext, elapsedMs: Long): DanmakuFrame? {
            if (item.optString("type") != "comment") return null
            val message = item.optString("message").takeIf(String::isNotBlank) ?: return null
            val author = item.optJSONObject("author")
            return ChatEventFactory.event(context, "twitcasting", message, item.optString("id"),
                author?.optString("id"), author?.optString("name"),
                sourceTimeMs = item.optLong("createdAt").takeIf { it > 0 }, elapsedMs = elapsedMs)
        }
    }
}
