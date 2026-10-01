package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuEventStore
import com.streamcap.mobile.danmaku.DanmakuFrame
import com.streamcap.mobile.danmaku.DanmakuSubtitleExporter
import com.streamcap.mobile.danmaku.DanmakuSubtitleFormat
import com.streamcap.mobile.danmaku.FakeDanmakuClient
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuSimulationTest {
    @Test fun fakeEventsPersistRecoverAndExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "danmaku-qa-${System.nanoTime()}").apply { mkdirs() }
        try {
            val source = File(directory, "events.jsonl")
            DanmakuEventStore(source).use { store ->
                FakeDanmakuClient(listOf("开始", "结束"))
                    .connect(DanmakuConnectContext(42, "simulation", "local"))
                    .collect { frame ->
                        if (frame is DanmakuFrame.Event) store.append(frame.event.copy(mediaTimeMs = if (frame.event.eventId == "fake-0") 1_000 else 2_000))
                    }
            }
            val restored = DanmakuEventStore.recover(source)
            assertEquals(2, restored.size)
            assertEquals("开始", restored.first().text)
            assertTrue(File(directory, "committed.marker").isFile)
            for (format in DanmakuSubtitleFormat.entries) {
                val target = File(directory, "output.${format.name.lowercase()}")
                DanmakuSubtitleExporter.export(restored, target, format)
                assertTrue(target.length() > 0)
            }
        } finally { directory.deleteRecursively() }
    }
}
