package com.streamcap.mobile.danmaku

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.streamcap.mobile.data.preferences.DanmakuAppearance

class DanmakuTest {
    private fun event(time: Long, id: String = "1", text: String = "hello") = DanmakuEvent(1, "test", id, time, time, "boot", text = text)

    @Test fun mapsOnlyWithinSameClockEpoch() {
        val mapper = TimelineMapper(listOf(MediaSegment("a", "boot", 100, 200, 0, 10_000)))
        assertEquals(5_000L, mapper.map(event(150)).mediaTimeMs)
        assertEquals("unmapped", mapper.map(event(150).copy(clockEpochId = "other")).timeQuality)
    }

    @Test fun exportsAssEscapingAndSkipsUnmapped() {
        val dir = createTempDirectory().toFile()
        val target = File(dir, "events.ass")
        DanmakuSubtitleExporter.export(listOf(event(0).copy(mediaTimeMs = 1_000, text = "a {b}"), event(1)), target, DanmakuSubtitleFormat.ASS)
        val content = target.readText()
        assertTrue(content.contains("\\{b\\}"))
        assertTrue(content.contains("PlayResX: 1920"))
        assertTrue(Regex("""\\an7\\move\(1920,(\d+),-\d+,\1\)""").containsMatchIn(content))
        assertTrue(content.contains("Dialogue: 0,0:00:01.00,"))
        assertEquals(1, content.lines().count { it.startsWith("Dialogue:") })
    }

    @Test fun recoversCompleteLinesAndDropsPartialTail() {
        val file = File(createTempDirectory().toFile(), "events.jsonl")
        DanmakuEventStore(file).use { store ->
            assertTrue(store.append(event(100, text = "中文")))
        }
        file.appendText("{partial")
        val recovered = DanmakuEventStore.recover(file)
        assertEquals(1, recovered.size)
        assertEquals("中文", recovered.single().text)
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test fun assAppliesAppearanceAndSpeedWhileKeepingSourceText() {
        val target = File(createTempDirectory().toFile(), "style.ass")
        val events = listOf(event(0).copy(mediaTimeMs = 1_000, text = "hello"))
        DanmakuSubtitleExporter.export(events, target, DanmakuSubtitleFormat.ASS,
            DanmakuAppearance(fontSizeSp = 20f, opacity = 1f, speed = 2f, colorRgb = 0x123456))
        val content = target.readText()
        assertTrue(content.contains("Noto Sans CJK SC,50,&H00563412"))
        assertTrue(Regex("""\\move\(1920,(\d+),-266,\1\)""").containsMatchIn(content))
        assertTrue(content.contains("0:00:01.00,0:00:04.70"))
        assertTrue(content.endsWith("hello"))
    }
}
