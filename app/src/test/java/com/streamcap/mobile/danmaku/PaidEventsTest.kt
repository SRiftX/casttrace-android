package com.streamcap.mobile.danmaku

import com.streamcap.mobile.data.preferences.DanmakuAppearance
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PaidEventsTest {
    private val context = DanmakuConnectContext(42, "test", "https://example.test")
    private fun bili(cmd: String, data: String): DanmakuEvent {
        val frames = mutableListOf<DanmakuFrame>()
        val json = JSONObject().put("cmd", cmd).put("data", JSONObject(data))
        BilibiliDanmakuClient.parsePackets(BilibiliDanmakuClient.packet(5, json.toString().toByteArray()).toByteArray(),
            context, 500, emit = frames::add)
        return (frames.single() as DanmakuFrame.Event).event
    }

    @Test fun bilibiliScRetainsMoneyIdentityAndDurationAndRendersAmount() {
        val e = bili("SUPER_CHAT_MESSAGE", """{"id":100,"uid":7,"price":30,"message":"加油！","start_time":1700000000,"end_time":1700000060,"user_info":{"uname":"观众","face":"https://example.test/a.png"}}""")
        assertEquals(DanmakuEventType.SUPER_CHAT, e.eventType)
        assertEquals(30L, e.amount); assertEquals("CNY", e.currency)
        assertEquals("7", e.userId); assertEquals("观众", e.userName)
        assertEquals(1700000000000L, e.sourceTimeMs)
        assertEquals("1700000060", e.attributes["end_time"])
        assertTrue(DanmakuPresentation.text(e, DanmakuAppearance())!!.contains("¥30"))
        assertNull(DanmakuPresentation.text(e, DanmakuAppearance(showSuperChat = false)))
    }

    @Test fun bilibiliGiftDoesNotTreatCoinsAsCnyOrComboBroadcastAsNewGift() {
        val e = bili("SEND_GIFT", """{"tid":"tx","uid":8,"uname":"粉丝","giftId":1,"giftName":"辣条","num":5,"total_coin":500,"coin_type":"silver","combo_id":"combo","combo_num":10}""")
        assertEquals("BILIBILI_SILVER", e.currency); assertEquals(500L, e.amount)
        assertEquals("5", e.attributes["quantity"]); assertEquals("increment", e.attributes["quantityMode"])
        assertEquals("combo", e.attributes["combo_id"])
        assertNull(BilibiliPaidEvents.parse(JSONObject("""{"cmd":"COMBO_SEND","data":{"combo_num":10}}"""), context, 500))
    }

    @Test fun guardHasLevelAndQuantityWithoutInventingOrderIdOrMoney() {
        val e = bili("GUARD_BUY", """{"uid":9,"username":"船员","guard_level":3,"num":1,"price":198000,"start_time":1700000000}""")
        assertEquals(DanmakuEventType.GUARD, e.eventType)
        assertEquals(3, e.user?.guardLevel); assertEquals("船员", e.userName)
        assertNull(e.eventId); assertNull(e.amount)
        assertEquals("198000", e.attributes["platformPrice"])
        assertNull(DanmakuPresentation.text(e, DanmakuAppearance(showGift = false)))
    }

    @Test fun malformedAndUnknownBiliEventsAreNotInvented() {
        for (json in listOf("""{"cmd":"SEND_GIFT","data":{"num":0}}""",
            """{"cmd":"SUPER_CHAT_MESSAGE","data":{"price":30}}""", """{"cmd":"UNKNOWN","data":{}}""")) {
            assertNull(BilibiliPaidEvents.parse(JSONObject(json), context, 100))
        }
    }

    private fun dy(count: Long, end: Long): ByteArray =
        ProtoWire.encodeBytes(1, ProtoWire.encodeVarint(2, 100) + ProtoWire.encodeVarint(4, 1700000000)) +
        ProtoWire.encodeVarint(2, 66) + ProtoWire.encodeVarint(5, count) +
        ProtoWire.encodeBytes(7, ProtoWire.encodeVarint(1, 123) + ProtoWire.encodeString(3, "粉丝")) +
        ProtoWire.encodeVarint(9, end) + ProtoWire.encodeVarint(11, 999) +
        ProtoWire.encodeBytes(15, ProtoWire.encodeVarint(10, 1) + ProtoWire.encodeVarint(12, 10) + ProtoWire.encodeString(16, "玫瑰"))

    @Test fun douyinComboUpdatesKeepCumulativeSemanticsAndDistinctIds() {
        val first = ProtobufGiftEvents.douyin(dy(1, 0), context, 100)!!
        val last = ProtobufGiftEvents.douyin(dy(3, 1), context, 200)!!
        assertEquals("123", last.userId); assertEquals("粉丝", last.userName)
        assertEquals(30L, last.amount); assertEquals("DOUYIN_DIAMOND", last.currency)
        assertEquals("cumulative", last.attributes["quantityMode"])
        assertEquals("3", last.attributes["quantity"])
        assertNotEquals(first.eventId, last.eventId)
        assertEquals(last.eventId, ProtobufGiftEvents.douyin(dy(3, 1), context, 300)!!.eventId)
        assertNull(ProtobufGiftEvents.douyin(byteArrayOf(1), context, 100))
    }

    @Test fun kuaishouMixedChatAndGiftRetainsBothWithoutInventingPrice() {
        val user = ProtoWire.encodeString(1, "uid") + ProtoWire.encodeString(2, "观众")
        val comment = ProtoWire.encodeBytes(2, user) + ProtoWire.encodeString(3, "hello")
        val gift = ProtoWire.encodeString(1, "gift1") + ProtoWire.encodeBytes(2, user) +
            ProtoWire.encodeVarint(3, 1700000000000) + ProtoWire.encodeVarint(4, 42) +
            ProtoWire.encodeString(6, "combo") + ProtoWire.encodeVarint(7, 2) + ProtoWire.encodeVarint(8, 3)
        val frames = KuaishouDanmakuClient.parse(KuaishouDanmakuClient.envelope(310,
            ProtoWire.encodeBytes(5, comment) + ProtoWire.encodeBytes(9, gift)), context, 100)
        assertEquals(2, frames.size)
        val e = (frames.last() as DanmakuFrame.Event).event
        assertEquals(DanmakuEventType.GIFT, e.eventType); assertEquals("6", e.attributes["quantity"])
        assertEquals("combo", e.attributes["comboId"]); assertEquals("uid", e.userId)
        assertNull(e.amount); assertNull(e.currency)
    }

    @Test fun douyuGiftPacketKeepsCountAndDoesNotInventPrice() {
        val frames = mutableListOf<DanmakuFrame>()
        DouyuDanmakuClient.parsePackets(DouyuDanmakuClient.packet("type@=dgb/uid@=12/nn@=观众/gfid@=42/gfcnt@=5/hits@=3/").toByteArray(), context, 100, frames::add)
        val e = (frames.single() as DanmakuFrame.Event).event
        assertEquals(DanmakuEventType.GIFT, e.eventType)
        assertEquals("5", e.attributes["quantity"]); assertEquals("3", e.attributes["hits"])
        assertNull(e.amount); assertNull(e.eventId)
    }

    @Test fun huyaGiftTarsKeepsIdentityTransactionAndCount() {
        val payload = TarsWire.encode(mapOf(0 to 42, 1 to "pay1", 2 to 5, 4 to 123L, 6 to "观众", 8 to 5, 9 to 2))
        val e = HuyaDanmakuClient.parsePush(TarsWire.encode(mapOf(1 to 6501L, 2 to payload)), context, 100)!!
        assertEquals(DanmakuEventType.GIFT, e.eventType); assertEquals("123", e.userId)
        assertEquals("5", e.attributes["quantity"]); assertEquals("pay1", e.attributes["payId"])
        assertNull(e.amount)
        val chat = HuyaDanmakuClient.parsePush(TarsWire.encode(mapOf(1 to 1400L,
            2 to TarsWire.encode(mapOf(0 to mapOf(0 to 123L, 2 to "观众"), 3 to "hello")))), context, 100)!!
        assertEquals(DanmakuEventType.CHAT, chat.eventType)
    }

    @Test fun twitchCheersAndGiftSubscriptionsStayInTheirOwnChannel() {
        val line = "@id=1;bits=100;user-id=7;display-name=Fan :fan!fan@fan PRIVMSG #eslcs :cheer100"
        val e = (TwitchDanmakuClient.parseLine(line, "eslcs", context, 100) as DanmakuFrame.Event).event
        assertEquals(100L, e.amount); assertEquals("TWITCH_BITS", e.currency)
        assertNull(TwitchDanmakuClient.parseLine(line, "other", context, 100))
        val notice = "@id=2;msg-id=submysterygift;msg-param-mass-gift-count=5;display-name=Fan :tmi.twitch.tv USERNOTICE #eslcs"
        val gift = (TwitchDanmakuClient.parseLine(notice, "eslcs", context, 100) as DanmakuFrame.Event).event
        assertEquals("5", gift.attributes["quantity"]); assertEquals("summary", gift.attributes["quantityMode"])
        assertNull(gift.amount)
    }

    @Test fun chzzkDonationUsesNativeCheeseAndSkipsHidden() {
        val root = JSONObject("""{"cmd":93102,"bdy":[{"msgTypeCode":10,"msg":"加油","extras":{"payAmount":1000,"donationType":"CHAT"},"profile":"{\"nickname\":\"Fan\"}"},{"msgTypeCode":10,"msgStatusType":"HIDDEN","extras":{"payAmount":500}}]}""")
        val e = (ChzzkDanmakuClient.parseChat(root, context, 100).single() as DanmakuFrame.Event).event
        assertEquals("CHZZK_CHEESE", e.currency); assertEquals(1000L, e.amount)
        assertEquals("Fan", e.userName)
    }

    @Test fun paidEventsSurviveDiskRoundTripAndFilteredReplay() {
        val dir = java.nio.file.Files.createTempDirectory("paid-events").toFile()
        try {
            val file = File(dir, "events.jsonl")
            val e = bili("SUPER_CHAT_MESSAGE", """{"id":1,"uid":2,"price":30,"message":"你好","user_info":{"uname":"Fan"}}""").copy(mediaTimeMs = 1234)
            DanmakuEventStore(file).use { assertTrue(it.append(e)) }
            val restored = DanmakuEventStore.readWindow(file, 0, 2000, filter = DanmakuFilter(eventType = DanmakuEventType.SUPER_CHAT)).single()
            assertEquals(e, restored)
            val srt = File(dir, "sc.srt")
            DanmakuSubtitleExporter.exportFile(file, 42, srt, DanmakuSubtitleFormat.SRT)
            assertTrue(srt.readText().contains("¥30"))
            val json = File(dir, "export.jsonl")
            DanmakuSubtitleExporter.exportFile(file, 42, json, DanmakuSubtitleFormat.JSONL)
            assertEquals(e, fromJson(JSONObject(json.readText().trim())))
        } finally { dir.deleteRecursively() }
    }

    @Test fun multiplicationOverflowNeverCreatesFakeMoney() {
        assertNull(PaidEventFactory.product(Long.MAX_VALUE, 2))
        assertNull(PaidEventFactory.product(-1, 2))
    }
}
