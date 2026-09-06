package com.streamcap.mobile.platform.resolver

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URLDecoder

internal object DouyinPageParser {
    data class Result(val anchor: String?, val title: String?, val sourceText: String)

    fun parse(html: String): Result {
        val document = Jsoup.parse(html)
        val payloads = mutableListOf<String>()
        document.select("script").forEach { script ->
            val raw = script.data().trim()
            when {
                script.id() == "RENDER_DATA" -> payloads += URLDecoder.decode(raw, "UTF-8")
                raw.startsWith("self.__pace_f.push(") -> {
                    val array = runCatching { JSONArray(raw.substringAfter("push(").substringBeforeLast(')')) }.getOrNull()
                    val payload = array?.optString(1).orEmpty()
                    if (payload.isNotBlank()) payloads += payload
                }
                script.attr("type") == "application/json" -> payloads += raw
            }
        }
        val roomInfo = payloads.firstNotNullOfOrNull { payload ->
            val json = if (payload.startsWith('{') || payload.startsWith('[')) payload else payload.substringAfter(':', "")
            findRoom(parseJson(json))
        }
        val room = roomInfo?.optJSONObject("room") ?: roomInfo
        val anchor = room?.optJSONObject("owner")?.cleanString("nickname")
            ?: roomInfo?.optJSONObject("anchor")?.cleanString("nickname")
            ?: document.select("[data-anchor-info]").firstNotNullOfOrNull {
                (parseJson(it.attr("data-anchor-info")) as? JSONObject)?.cleanString("nickname")
            }
        return Result(anchor, room?.cleanString("title"), payloads.joinToString("\n") + "\n" + html)
    }

    private fun parseJson(text: String): Any? = runCatching { JSONTokener(text).nextValue() }.getOrNull()

    private fun findRoom(value: Any?, depth: Int = 0): JSONObject? {
        if (depth > 30) return null
        when (value) {
            is JSONObject -> {
                val info = value.optJSONObject("roomStore")?.optJSONObject("roomInfo")
                    ?: value.optJSONObject("roomInfo")
                if (info?.optJSONObject("room") != null) return info
                if (value.optJSONObject("owner") != null && value.has("title") && value.has("status")) return value
                value.keys().forEach { key -> findRoom(value.opt(key), depth + 1)?.let { return it } }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                findRoom(value.opt(index), depth + 1)?.let { return it }
            }
            is String -> if (value.startsWith('{') || value.startsWith('[')) {
                findRoom(parseJson(value), depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.cleanString(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" && it != "\$undefined" }
}
