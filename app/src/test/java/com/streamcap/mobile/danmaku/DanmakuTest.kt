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

    @Test fun readsOnlyBoundedMediaWindow() {
        val file = File(createTempDirectory().toFile(), "window.jsonl")
        DanmakuEventStore(file).use { store ->
            repeat(20) { index ->
                store.append(event(index * 1_000L, id = index.toString()).copy(mediaTimeMs = index * 1_000L))
            }
        }
        val window = DanmakuEventStore.readWindow(file, 5_000L, 10_000L, maxEvents = 3)
        assertEquals(listOf(5_000L, 6_000L, 7_000L), window.mapNotNull { it.mediaTimeMs })
    }

    @Test fun windowIndexHandlesLargeAndOutOfOrderAssets() {
        val file = File(createTempDirectory().toFile(), "large.jsonl")
        DanmakuEventStore(file, capacity = 2_048).use { store ->
            repeat(1_000) { index ->
                val time = if (index == 400) 5_500L else index * 1_000L
                assertTrue(store.append(event(index.toLong(), id = index.toString()).copy(mediaTimeMs = time)))
            }
        }
        assertEquals(listOf(5_000L, 6_000L, 5_500L),
            DanmakuEventStore.readWindow(file, 5_000L, 7_000L).mapNotNull { it.mediaTimeMs })
        assertTrue(File(file.parentFile, "${file.name}.time-index").isFile)
        DanmakuEventStore.clearIndexCacheForTest()
        assertEquals(1, DanmakuEventStore.readWindow(file, 5_500L, 5_501L).size)
        DanmakuEventStore(file).use { it.append(event(1_001).copy(mediaTimeMs = 1_001_000L)) }
        assertEquals(1, DanmakuEventStore.readWindow(file, 1_001_000L, 1_002_000L).size)
        assertEquals(1_001L, org.json.JSONObject(File(file.parentFile, "coverage.json").readText()).getLong("eventCount"))
    }

    @Test fun pagesFilteredEventsAcrossBlocksWithoutChangingTheFile() {
        val file = File(createTempDirectory().toFile(), "pages.jsonl")
        DanmakuEventStore(file, capacity = 1_024).use { store ->
            repeat(600) { index ->
                assertTrue(store.append(event(index.toLong(), id = index.toString(), text = "message $index")
                    .copy(mediaTimeMs = if (index == 300) null else index * 1_000L,
                        platform = if (index % 2 == 0) "bilibili" else "douyu",
                        user = DanmakuUser(index.toString(), if (index % 2 == 0) "Alice" else "Bob"),
                        eventType = if (index % 2 == 0) DanmakuEventType.CHAT else DanmakuEventType.GIFT)))
            }
        }
        val before = file.readBytes()
        val filter = DanmakuFilter(query = "message", user = "Alice", platform = "bilibili", eventType = DanmakuEventType.CHAT)
        val first = DanmakuEventStore.readPage(file, 1, limit = 100, filter = filter)
        val second = DanmakuEventStore.readPage(file, 1, offset = requireNotNull(first.nextOffset), limit = 100, filter = filter)
        val third = DanmakuEventStore.readPage(file, 1, offset = requireNotNull(second.nextOffset), limit = 100, filter = filter)
        val finalPage = third.nextOffset?.let { DanmakuEventStore.readPage(file, 1, offset = it, limit = 100, filter = filter) }
        assertEquals(300, (first.events + second.events + third.events).size)
        assertEquals(null, finalPage?.nextOffset)
        assertEquals(null, (first.events + second.events + third.events).first { it.eventId == "300" }.mediaTimeMs)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(300L, DanmakuEventStore.countMatching(file, 1, filter))
    }

    @Test fun pageIgnoresIncompleteTailWithoutTruncatingIt() {
        val file = File(createTempDirectory().toFile(), "tail.jsonl")
        DanmakuEventStore(file).use { it.append(event(1).copy(mediaTimeMs = 1)) }
        file.appendText("{partial")
        val page = DanmakuEventStore.readPage(file, 1)
        assertEquals(1, page.events.size)
        assertEquals(null, page.nextOffset)
        assertTrue(file.readText().endsWith("{partial"))
    }

    @Test fun pagesLegacyEventsWithMissingOptionalFields() {
        val file = File(createTempDirectory().toFile(), "legacy.jsonl")
        val old = event(1, text = "legacy").toJson().apply {
            remove("eventType"); remove("user"); remove("amount"); remove("currency"); remove("colorRgb")
            remove("mediaTimeMs")
        }
        file.writeText(old.toString() + "\n")
        val restored = DanmakuEventStore.readPage(file, 1, filter = DanmakuFilter(query = "legacy")).events.single()
        assertEquals(DanmakuEventType.CHAT, restored.eventType)
        assertEquals(null, restored.mediaTimeMs)
    }

    @Test fun derivesEventTypeAndKeepsUserFields() {
        val gift = event(0).copy(rawType = "gift", eventType = DanmakuEventType.GIFT, user = DanmakuUser("7", "主播粉丝"), amount = 3)
        val file = File(createTempDirectory().toFile(), "events.jsonl")
        DanmakuEventStore(file).use { it.append(gift) }
        val restored = DanmakuEventStore.recover(file).single()
        assertEquals(DanmakuEventType.GIFT, restored.eventType)
        assertEquals("主播粉丝", restored.user?.name)
        assertEquals(3L, restored.amount)
        val counts = org.json.JSONObject(File(file.parentFile, "coverage.json").readText()).getJSONObject("eventTypes")
        assertEquals(1L, counts.getLong("GIFT"))
    }

    @Test fun presentationMatchesUserAndEventSettings() {
        val gift = event(0, text = "谢谢支持").copy(eventType = DanmakuEventType.GIFT,
            user = DanmakuUser("42", "观众"))
        assertEquals("[礼物] 观众：谢谢支持", DanmakuPresentation.text(gift, DanmakuAppearance()))
        assertEquals("谢谢支持", DanmakuPresentation.text(gift,
            DanmakuAppearance(showUserName = false, showEventTag = false)))
        assertEquals(null, DanmakuPresentation.text(gift.copy(eventType = DanmakuEventType.SYSTEM), DanmakuAppearance()))
    }

    @Test fun jsonlExportKeepsUnifiedEventFields() {
        val target = File(createTempDirectory().toFile(), "events.jsonl")
        val gift = event(0).copy(eventType = DanmakuEventType.GIFT,
            user = DanmakuUser("42", "观众"), amount = 300, currency = "CNY", mediaTimeMs = 1_000)
        DanmakuSubtitleExporter.export(listOf(gift), target, DanmakuSubtitleFormat.JSONL)
        val exported = org.json.JSONObject(target.readText())
        assertEquals("GIFT", exported.getString("eventType"))
        assertEquals("42", exported.getJSONObject("user").getString("id"))
        assertEquals(300L, exported.getLong("amount"))
    }

    @Test fun segmentArchiveRoutesEventsAndKeepsUnmapped() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "events.jsonl")
        DanmakuEventStore(source).use { store ->
            store.append(event(1, "a").copy(mediaTimeMs = 999))
            store.append(event(2, "b").copy(mediaTimeMs = 1_000))
            store.append(event(3, "c").copy(mediaTimeMs = null))
        }
        val archive = DanmakuSegmentArchive.seal(source, 1, listOf("uri:a", "uri:b"), listOf(1_000, 2_000))
        val manifest = org.json.JSONObject(File(archive, "manifest.json").readText())
        assertEquals(2, manifest.getJSONArray("segments").length())
        assertEquals("part-0001", DanmakuEventStore.recover(File(archive, "part-0001.jsonl")).single().segmentId)
        assertEquals("part-0002", DanmakuEventStore.recover(File(archive, "part-0002.jsonl")).single().segmentId)
        assertEquals(null, DanmakuEventStore.recover(File(archive, "unmapped.jsonl")).single().segmentId)
    }

    @Test fun streamedSrtSortsOutOfOrderEventsWithoutDroppingDenseCues() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "events.jsonl")
        DanmakuEventStore(source).use { store ->
            store.append(event(1, "a").copy(mediaTimeMs = 2_000))
            store.append(event(2, "b").copy(mediaTimeMs = 1_000))
        }
        val target = File(dir, "events.srt")
        DanmakuSubtitleExporter.exportFile(source, 1, target, DanmakuSubtitleFormat.SRT)
        val content = target.readText()
        assertTrue(content.indexOf("00:00:01,000") < content.indexOf("00:00:02,000"))
        assertEquals(2, content.lines().count { it.contains(" --> ") })
    }

    @Test fun segmentArchiveReopensEvictedWritersWithoutLosingEvents() {
        val dir = createTempDirectory().toFile()
        val source = File(dir, "events.jsonl")
        DanmakuEventStore(source).use { store ->
            repeat(6) { index -> assertTrue(store.append(event(index.toLong(), index.toString()).copy(mediaTimeMs = index * 1_000L))) }
            assertTrue(store.append(event(7, "again").copy(mediaTimeMs = 100L)))
        }
        val archive = DanmakuSegmentArchive.seal(source, 1, (1..6).map { "uri:$it" }, List(6) { 1_000L })
        assertEquals(listOf("0", "again"), DanmakuEventStore.recover(File(archive, "part-0001.jsonl")).map { it.eventId })
    }

    @Test fun highRateInputAccountsForEveryAcceptedOrDroppedEvent() {
        val file = File(createTempDirectory().toFile(), "burst.jsonl")
        val store = DanmakuEventStore(file, capacity = 8)
        repeat(60_000) { index -> store.append(event(index.toLong(), index.toString())) }
        store.close()
        val stats = store.stats()
        assertEquals(60_000L, stats.written + stats.dropped)
        assertEquals(stats.written, DanmakuEventStore.countMatching(file, 1, DanmakuFilter()))
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
