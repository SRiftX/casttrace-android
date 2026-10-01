package com.streamcap.mobile.platform.resolver

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class DouyinPageParserTest {
    @Test fun preservesQualityLabelsFromNestedStreamDataInsteadOfUrlOrder() {
        val info = roomInfo()
        val data = JSONObject().put("data", JSONObject()
            .put("sd", JSONObject().put("main", JSONObject().put("hls", "https://cdn/low.m3u8")))
            .put("origin", JSONObject().put("main", JSONObject().put("hls", "https://cdn/high.m3u8"))))
        info.getJSONObject("room").put("stream_url", JSONObject()
            .put("live_core_sdk_data", JSONObject().put("pull_data", JSONObject().put("stream_data", data.toString()))))
        val parsed = DouyinPageParser.parse("<script type='application/json'>${JSONObject().put("roomInfo", info)}</script>")
        assertEquals("原画", parsed.sources.single { it.url.endsWith("high.m3u8") }.quality)
        assertEquals("https://cdn/high.m3u8", parsed.sources.selectQuality("原画").single().url)
    }
    private fun roomInfo() = JSONObject().put("room", JSONObject()
        .put("title", """桌面 \ 展示 & 原画""")
        .put("status", 2)
        .put("owner", JSONObject().put("nickname", "测试店铺")))

    @Test fun decodesFlightPayloadAndIgnoresVisitorNickname() {
        val state = JSONObject().put("visitor", JSONObject().put("nickname", "wrong user"))
            .put("roomStore", JSONObject().put("roomInfo", roomInfo()))
        val payload = "d:" + JSONArray().put("component").put(JSONObject().put("state", state))
        val push = JSONArray().put(1).put(payload)
        val parsed = DouyinPageParser.parse("<script>self.__pace_f.push($push)</script>")
        assertEquals("测试店铺", parsed.anchor)
        assertEquals(roomInfo().getJSONObject("room").getString("title"), parsed.title)
    }

    @Test fun decodesPercentEncodedRenderData() {
        val data = JSONObject().put("roomInfo", roomInfo()).toString()
        val parsed = DouyinPageParser.parse("<script id='RENDER_DATA'>${URLEncoder.encode(data, "UTF-8")}</script>")
        assertEquals("测试店铺", parsed.anchor)
        assertNotNull(parsed.title)
    }

    @Test fun decodesHtmlAttributeWithoutUsingUnrelatedPageTitle() {
        val parsed = DouyinPageParser.parse("""<title>Site navigation</title><div data-anchor-info='{&quot;nickname&quot;:&quot;A &amp; B&quot;}'></div>""")
        assertEquals("A & B", parsed.anchor)
        assertNull(parsed.title)
    }

    @Test fun missingRoomDataDoesNotUseUnrelatedNickname() {
        val parsed = DouyinPageParser.parse("""<script type='application/json'>{"visitor":{"nickname":"wrong user"}}</script>""")
        assertNull(parsed.anchor)
        assertNull(parsed.title)
    }

    @Test fun parsesMobileReflowRscWithoutRoundingRoomId() {
        val room = JSONObject().put("id", 7691516284797767000L).put("idStr", "7691516284797766427")
            .put("status", 2).put("title", "戟创电竞桌/电脑桌销量榜TOP1")
            .put("owner", JSONObject().put("nickname", "戟创官方旗舰店").put("webRid", "5634899055"))
            .put("streamUrl", JSONObject().put("hlsPullUrlMap", JSONObject().put("FULL_HD1", "//cdn.example/origin.m3u8")))
        val payload = "5:" + JSONArray().put("component").put(JSONObject().put("data", JSONObject().put("room", room)))
        val html = "<script>self.__rsc_f.push(${JSONArray().put(1).put(payload)})</script>"
        val parsed = DouyinPageParser.parse(html)
        assertEquals("戟创官方旗舰店", parsed.anchor)
        assertEquals("7691516284797766427", parsed.roomId)
        assertEquals("5634899055", parsed.webRoomId)
        assertEquals("https://cdn.example/origin.m3u8", parsed.sources.single().url)
        assertEquals("原画", parsed.sources.single().quality)
    }
}
