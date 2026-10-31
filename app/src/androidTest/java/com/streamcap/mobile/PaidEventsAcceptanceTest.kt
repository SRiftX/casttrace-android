package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Protocol fixtures, not paid live transactions. Exercises Android JSON and disk/export paths. */
class PaidEventsAcceptanceTest {
    @Test fun bilibiliPaidPacketsPersistAndExportOnAndroid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(app.cacheDir, "paid-fixture-${System.nanoTime()}").apply { mkdirs() }
        try {
            val context = DanmakuConnectContext(42, "bilibili", "https://live.bilibili.com/6")
            val packets = listOf(
                """{"cmd":"SUPER_CHAT_MESSAGE","data":{"id":1,"uid":2,"price":30,"message":"fixture SC","user_info":{"uname":"Fixture"}}}""",
                """{"cmd":"SEND_GIFT","data":{"tid":"fixture","uid":2,"uname":"Fixture","giftId":1,"giftName":"fixture gift","num":3,"total_coin":300,"coin_type":"gold"}}""",
                """{"cmd":"GUARD_BUY","data":{"uid":2,"username":"Fixture","guard_level":3,"num":1,"price":198000}}""",
            ).map { BilibiliDanmakuClient.packet(5, it.toByteArray()).toByteArray() }
            val events = mutableListOf<DanmakuEvent>()
            BilibiliDanmakuClient.parsePackets(packets.reduce { a, b -> a + b }, context, 1000) { frame ->
                if (frame is DanmakuFrame.Event) events += frame.event.copy(mediaTimeMs = 1000)
            }
            assertEquals(listOf(DanmakuEventType.SUPER_CHAT, DanmakuEventType.GIFT, DanmakuEventType.GUARD), events.map { it.eventType })
            val file = File(dir, "events.jsonl")
            DanmakuEventStore(file).use { store -> events.forEach { assertTrue(store.append(it)) } }
            val recovered = DanmakuEventStore.readWindow(file, 0, 2000)
            assertEquals(events, recovered)
            assertEquals(30L, recovered[0].amount)
            assertEquals("CNY", recovered[0].currency)
            assertEquals("3", recovered[1].attributes["quantity"])
            val srt = File(dir, "events.srt")
            DanmakuSubtitleExporter.exportFile(file, 42, srt, DanmakuSubtitleFormat.SRT)
            assertTrue(srt.readText().contains("¥30"))
            val coverage = JSONObject(File(dir, "coverage.json").readText()).getJSONObject("eventTypes")
            assertEquals(1, coverage.getInt("SUPER_CHAT"))
            assertEquals(1, coverage.getInt("GIFT"))
            assertEquals(1, coverage.getInt("GUARD"))
        } finally { dir.deleteRecursively() }
    }
}
