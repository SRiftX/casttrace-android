package com.streamcap.mobile.platform.resolver

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class DouyinPageParserTest {
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
}
