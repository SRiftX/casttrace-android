package com.streamcap.mobile.danmaku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDanmakuProtocolTest {
    @Test fun wbiMatchesPublishedReferenceVector() {
        assertEquals("bar=514&foo=114&wts=1702204169&zab=1919810&w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4",
            BilibiliWbi.sign(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"),
                "7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45", 1702204169))
    }

    @Test fun douyuPacketEscapingAndChatParsing() {
        val context = DanmakuConnectContext(1, "douyu", "https://www.douyu.com/9999")
        val packet = DouyuDanmakuClient.packet("type@=chatmsg/uid@=42/nn@=主播@A一号/txt@=hello@Sworld/cid@=abc/").toByteArray()
        val frames = mutableListOf<DanmakuFrame>()
        DouyuDanmakuClient.parsePackets(packet, context, 100, frames::add)
        val event = (frames.single() as DanmakuFrame.Event).event
        assertEquals("主播@一号", event.userName)
        assertEquals("hello/world", event.text)
        assertEquals("douyu-websocket-1", event.parserVersion)
    }

    @Test fun bilibiliPacketParsesDanmuMessage() {
        val info = org.json.JSONArray().apply {
            put(0); put("你好 B站"); put(org.json.JSONArray().apply { put(1001); put("用户名") })
        }
        val json = org.json.JSONObject().apply { put("cmd", "DANMU_MSG:4:0:2:2:0:0:0:0"); put("info", info) }
        val frames = mutableListOf<DanmakuFrame>()
        BilibiliDanmakuClient.parsePackets(BilibiliDanmakuClient.packet(5, json.toString().toByteArray()).toByteArray(),
            DanmakuConnectContext(2, "bilibili", "https://live.bilibili.com/6"), 200, emit = frames::add)
        val event = (frames.single() as DanmakuFrame.Event).event
        assertEquals("用户名", event.userName)
        assertEquals("你好 B站", event.text)
        assertTrue(event.parserVersion.startsWith("bilibili-"))
    }

    @Test fun concatenatedDouyuPacketsKeepAllMessagesAndRejectBadLengths() {
        val context = DanmakuConnectContext(1, "douyu", "https://www.douyu.com/9999")
        val a = DouyuDanmakuClient.packet("type@=chatmsg/txt@=one/cid@=1/").toByteArray()
        val b = DouyuDanmakuClient.packet("type@=chatmsg/txt@=two/cid@=2/").toByteArray()
        val frames = mutableListOf<DanmakuFrame>()
        DouyuDanmakuClient.parsePackets(a + b, context, 100, frames::add)
        assertEquals(listOf("one", "two"), frames.map { (it as DanmakuFrame.Event).event.text })
        a[4] = 0
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DouyuDanmakuClient.parsePackets(a, context, 100) {}
        }
    }

    @Test fun bilibiliZlibNestedPacketsAndAuthErrors() {
        val context = DanmakuConnectContext(1, "bilibili", "https://live.bilibili.com/6")
        val auth = BilibiliDanmakuClient.packet(8, "{\"code\":0}".toByteArray()).toByteArray()
        val frames = mutableListOf<DanmakuFrame>()
        val compressed = java.io.ByteArrayOutputStream().apply {
            java.util.zip.DeflaterOutputStream(this).use { it.write(auth + auth) }
        }.toByteArray()
        BilibiliDanmakuClient.parsePackets(BilibiliDanmakuClient.packet(5, compressed, 2).toByteArray(), context, 100, emit = frames::add)
        assertEquals(2, frames.size)
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            BilibiliDanmakuClient.parsePackets(BilibiliDanmakuClient.packet(8, "{\"code\":-101}".toByteArray()).toByteArray(), context, 100) {}
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            BilibiliDanmakuClient.parsePackets(ByteArray(16), context, 100) {}
        }
    }
}
