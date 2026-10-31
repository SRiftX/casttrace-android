package com.streamcap.mobile.danmaku

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdditionalChatProtocolTest {
    private val context = DanmakuConnectContext(42, "test", "https://example.com")

    @Test fun twitcastingCommentsKeepRealIdsAndIgnoreOtherEvents() {
        val item = JSONObject("""{"type":"comment","id":33970840032,"message":"hello","createdAt":1793116566000,"author":{"id":"c:viewer","name":"Viewer"}}""")
        val event = (TwitCastingDanmakuClient.parseChat(item, context, 123) as DanmakuFrame.Event).event
        assertEquals("33970840032", event.eventId)
        assertEquals("c:viewer", event.userId)
        assertEquals("Viewer", event.userName)
        assertEquals(1793116566000L, event.sourceTimeMs)
        assertNull(TwitCastingDanmakuClient.parseChat(JSONObject("""{"type":"gift","message":"gift"}"""), context, 123))
        assertTrue(RealDanmakuClientFactory(okhttp3.OkHttpClient()).supports("twitcasting"))
    }

    @Test fun showroomPublicChatSkipsGiftEventsAndDoesNotInventMessageIds() {
        val item = JSONObject("""{"t":1,"u":123,"ac":"viewer","cm":"hello","created_at":1700000000}""")
        val event = (ShowroomDanmakuClient.parseChat(item, context, 123) as DanmakuFrame.Event).event
        assertEquals("viewer", event.userName)
        assertEquals("123", event.userId)
        assertEquals(1700000000000L, event.sourceTimeMs)
        assertNull(event.eventId)
        assertNull(ShowroomDanmakuClient.parseChat(JSONObject("""{"t":2,"cm":"gift"}"""), context, 123))
    }

    @Test fun kuaishouCurrentPageUsesActiveRoomAndLowercaseSocketField() {
        val info = KuaishouDanmakuClient.handshakeInfo("""window.__INITIAL_STATE__={"liveroom":{"activeIndex":1,"token":"fixture","websocketUrls":["wss://example.test"],"playList":[{"liveStream":{"id":"other"}},{"liveStream":{"id":"current"}}]}};""")
        assertEquals("current", info.getString("liveStreamId"))
        assertEquals("wss://example.test", info.getJSONArray("webSocketUrls").getString(0))
        assertEquals("fixture", info.getString("token"))
    }

    @Test fun webpageCaptureDoesNotInventAnIdentityOrAnEventId() {
        val event = (WebPageDanmakuClient.parseCaptured("yy", JSONObject("""{"name":"用户","text":"hello"}"""), context, 100) as DanmakuFrame.Event).event
        assertEquals("用户", event.userName)
        assertNull(event.userId)
        assertNull(event.eventId)
        assertEquals("yy-web-observer-1", event.parserVersion)
        assertNull(WebPageDanmakuClient.parseCaptured("rednote", JSONObject("""{"state":"observing"}"""), context, 100))
    }

    @Test fun twitchTagsAreDecodedAndMessagesStayInTheirOwnChannel() {
        val line = "@display-name=A\\sB\\:C;id=abc;user-id=123;tmi-sent-ts=1700000000000 :nick!nick@nick.tmi.twitch.tv PRIVMSG #eslcs :hello"
        val event = (TwitchDanmakuClient.parseLine(line, "eslcs", context, 123) as DanmakuFrame.Event).event
        assertEquals("A B;C", event.userName)
        assertEquals("123", event.userId)
        assertEquals("abc", event.eventId)
        assertEquals(1700000000000L, event.sourceTimeMs)
        assertNull(TwitchDanmakuClient.parseLine(line, "other", context, 123))
        assertNull(TwitchDanmakuClient.parseLine("PING :server", "eslcs", context, 123))
    }

    @Test fun chzzkPublicChatPreservesIdentityAndSkipsHiddenAndDonationEvents() {
        val root = JSONObject("""{"cmd":93101,"bdy":[{"msgTypeCode":1,"msg":"hello","uid":"hash","profile":"{\"nickname\":\"测试用户\",\"userIdHash\":\"hash\"}","msgTime":1700000000000},{"msgTypeCode":1,"msg":"hidden","msgStatusType":"HIDDEN"},{"msgTypeCode":10,"msg":"donation"}]}""")
        val event = (ChzzkDanmakuClient.parseChat(root, context, 456).single() as DanmakuFrame.Event).event
        assertEquals("测试用户", event.userName)
        assertEquals("hash", event.userId)
        assertEquals("hello", event.text)
        assertEquals(456L, event.receivedElapsedMs)
        assertNull(event.eventId)
    }

    @Test fun kuaishouBinaryChatPreservesIdentityAndIgnoresGifts() {
        val user = ProtoWire.encodeString(1, "uid") + ProtoWire.encodeString(2, "用户")
        val comment = ProtoWire.encodeString(1, "cid") + ProtoWire.encodeBytes(2, user) + ProtoWire.encodeString(3, "hello")
        val hidden = comment + ProtoWire.encodeVarint(7, 2)
        val push = ProtoWire.encodeBytes(5, comment) + ProtoWire.encodeBytes(5, hidden) + ProtoWire.encodeBytes(9, byteArrayOf(1))
        val frames = KuaishouDanmakuClient.parse(KuaishouDanmakuClient.envelope(310, push), context, 789)
        val event = (frames.single() as DanmakuFrame.Event).event
        assertEquals("uid", event.userId)
        assertEquals("用户", event.userName)
        assertEquals("cid", event.eventId)
        assertEquals("hello", event.text)
        assertEquals("subscribed-real", (KuaishouDanmakuClient.parse(KuaishouDanmakuClient.envelope(300, byteArrayOf()), context, 0).single() as DanmakuFrame.Status).state)
    }
}
