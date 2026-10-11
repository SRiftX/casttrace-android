package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuEventStore
import com.streamcap.mobile.danmaku.DanmakuFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

class DanmakuLongAssetTest {
    @Test fun largeAssetReadsSmallWindowsAndFilteredPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "qa-long-events.jsonl")
        try {
            val writeMs = measureTimeMillis {
                DanmakuEventStore(file, capacity = 2_048).use { store ->
                    repeat(50_000) { index ->
                        val event = DanmakuEvent(
                            sessionId = 9137, platform = if (index % 2 == 0) "bilibili" else "douyu",
                            eventId = index.toString(), receivedElapsedMs = index.toLong(),
                            receivedWallMs = index.toLong(), clockEpochId = "qa",
                            mediaTimeMs = index * 1_000L, userName = "user${index % 100}",
                            text = "message $index",
                        )
                        while (!store.append(event)) Thread.sleep(2)
                    }
                    while (store.stats().written < 50_000L) Thread.sleep(10)
                }
            }
            val firstMs = measureTimeMillis {
                val window = DanmakuEventStore.readWindow(file, 49_900_000, 50_000_000)
                assertEquals(100, window.size)
                assertEquals("49900", window.first().eventId)
            }
            val repeatedMs = measureTimeMillis {
                repeat(20) { step ->
                    assertEquals(100, DanmakuEventStore.readWindow(
                        file, step * 2_000_000L, step * 2_000_000L + 100_000L).size)
                }
            }
            DanmakuEventStore.clearIndexCacheForTest()
            val restoredMs = measureTimeMillis {
                assertEquals(100, DanmakuEventStore.readWindow(file, 49_900_000, 50_000_000).size)
            }
            val filterMs = measureTimeMillis {
                val page = DanmakuEventStore.readPage(file, 9137, limit = 100,
                    filter = DanmakuFilter(user = "user42", platform = "bilibili"))
                assertEquals(100, page.events.size)
                assertTrue(page.nextOffset != null)
            }
            println("LONG_DANMAKU_QA bytes=${file.length()} writeMs=$writeMs firstWindowMs=$firstMs repeated20Ms=$repeatedMs restoredIndexMs=$restoredMs filteredPageMs=$filterMs")
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.time-index").delete()
            File(file.parentFile, "coverage.json").delete()
            File(file.parentFile, "committed.marker").delete()
        }
    }
}
