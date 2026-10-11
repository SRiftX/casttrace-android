package com.streamcap.mobile.danmaku

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.PriorityQueue
import java.nio.charset.StandardCharsets
import com.streamcap.mobile.data.preferences.DanmakuAppearance

enum class DanmakuSubtitleFormat { SRT, ASS, JSONL, COVERAGE }

object DanmakuSubtitleExporter {
    /** Export directly from JSONL without materialising the complete recording. */
    fun exportFile(source: File, sessionId: Long, target: File, format: DanmakuSubtitleFormat,
                   appearance: DanmakuAppearance = DanmakuAppearance(), isCancelled: () -> Boolean = { false }) {
        target.parentFile?.mkdirs()
        if (format == DanmakuSubtitleFormat.COVERAGE) {
            File(source.parentFile, "coverage.json").copyTo(target, overwrite = true)
            return
        }
        if (format == DanmakuSubtitleFormat.JSONL) {
            source.inputStream().bufferedReader().use { input -> target.bufferedWriter().use { output ->
                input.forEachLine { line ->
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    runCatching { fromJson(org.json.JSONObject(line)) }
                        .getOrNull()?.takeIf { it.sessionId == sessionId }?.let { output.append(it.toJson().toString()).append('\n') }
                }
            } }
            return
        }
        require(format != DanmakuSubtitleFormat.COVERAGE) { "覆盖统计需要从录制资产导出" }
        val style = appearance.normalized()
        target.bufferedWriter().use { out ->
            if (format == DanmakuSubtitleFormat.ASS) out.append(assHeader(style))
            var subtitleIndex = 0
            val lineHeight = style.fontSizeSp * 2.5f + 16
            val lanes = ((1080 * style.area - 60) / lineHeight).toInt().coerceIn(1, style.laneLimit)
            val nextFree = LongArray(lanes)
            val speed = 1920f / (6500 / style.speed)
            forEachSortedEvent(source, sessionId, isCancelled) { event ->
                val body = DanmakuPresentation.text(event, style) ?: return@forEachSortedEvent
                val time = (event.mediaTimeMs ?: return@forEachSortedEvent) + style.timeOffsetMs
                if (time < 0L) return@forEachSortedEvent
                if (format == DanmakuSubtitleFormat.ASS && style.density < 1f &&
                    (event.eventId ?: "${event.receivedElapsedMs}:${event.text}").hashCode().toUInt().toLong() % 10_000 >= (style.density * 10_000).toLong()) return@forEachSortedEvent
                if (format == DanmakuSubtitleFormat.SRT) {
                    subtitleIndex++
                    out.append("$subtitleIndex\n${srtTime(time)} --> ${srtTime(time + 3_000)}\n$body\n\n")
                } else {
                    val width = (body.codePointCount(0, body.length) * style.fontSizeSp * 2.5f + 16f).coerceAtMost(2880f)
                    val lane = nextFree.indices.firstOrNull { nextFree[it] <= time } ?: nextFree.indices.minBy { nextFree[it] }
                    val start = maxOf(time, nextFree[lane])
                    if (start - time > 1_200) return@forEachSortedEvent
                    val end = start + kotlin.math.ceil((1920f + width) / speed).toLong()
                    nextFree[lane] = start + kotlin.math.ceil((width + 48f) / speed).toLong()
                    val y = 60 + lane * lineHeight.toInt()
                    val movement = "{\\an7\\move(1920,$y,${-width.toInt()},$y)}"
                    out.append("Dialogue: 0,${assTime(start)},${assTime(end)},Default,,0,0,0,,$movement${escapeAss(body)}\n")
                }
            }
        }
    }

    private data class EventPosition(val time: Long, val offset: Long)
    private data class MergeHead(val position: EventPosition, val run: Int)

    private fun forEachSortedEvent(source: File, sessionId: Long, isCancelled: () -> Boolean,
                                   consume: (DanmakuEvent) -> Unit) {
        val runs = mutableListOf<File>()
        val readers = mutableListOf<java.io.BufferedReader>()
        try {
            RandomAccessFile(source, "r").use { input ->
                val end = input.length()
                val positions = ArrayList<EventPosition>(50_000)
                fun flush() {
                    if (positions.isEmpty()) return
                    val run = File.createTempFile("danmaku-sort-", ".idx", source.parentFile)
                    run.bufferedWriter().use { out ->
                        positions.sortedWith(compareBy<EventPosition> { it.time }.thenBy { it.offset })
                            .forEach { out.append("${it.time}\t${it.offset}\n") }
                    }
                    runs += run
                    positions.clear()
                }
                while (input.filePointer < end) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val offset = input.filePointer
                    val line = input.readLine() ?: break
                    val event = runCatching { fromJson(org.json.JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }.getOrNull()
                    if (event?.sessionId == sessionId && event.mediaTimeMs != null && event.mediaTimeMs >= 0) {
                        positions += EventPosition(event.mediaTimeMs, offset)
                        if (positions.size == 50_000) flush()
                    }
                }
                flush()
                fun parse(line: String): EventPosition? {
                    val split = line.indexOf('\t')
                    if (split < 0) return null
                    return EventPosition(line.substring(0, split).toLong(), line.substring(split + 1).toLong())
                }
                val heap = PriorityQueue(compareBy<MergeHead> { it.position.time }.thenBy { it.position.offset })
                runs.forEachIndexed { index, run ->
                    val reader = run.bufferedReader()
                    readers += reader
                    reader.readLine()?.let { heap += MergeHead(requireNotNull(parse(it)), index) }
                }
                while (heap.isNotEmpty()) {
                    if (isCancelled()) throw java.util.concurrent.CancellationException()
                    val head = heap.remove()
                    input.seek(head.position.offset)
                    input.readLine()?.let { line ->
                        runCatching { fromJson(org.json.JSONObject(String(line.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8))) }
                            .getOrNull()?.let(consume)
                    }
                    readers[head.run].readLine()?.let { heap += MergeHead(requireNotNull(parse(it)), head.run) }
                }
            }
        } finally {
            readers.forEach { it.close() }
            runs.forEach { it.delete() }
        }
    }

    fun export(events: List<DanmakuEvent>, target: File, format: DanmakuSubtitleFormat,
               appearance: DanmakuAppearance = DanmakuAppearance()) {
        target.parentFile?.mkdirs()
        if (format == DanmakuSubtitleFormat.JSONL) {
            target.bufferedWriter().use { out ->
                events.forEach { event -> out.append(event.toJson().toString()).append('\n') }
            }
            return
        }
        val style = appearance.normalized()
        val lineHeight = style.fontSizeSp * 2.5f + 16
        val lanes = ((1080 * style.area - 60) / lineHeight).toInt().coerceIn(1, style.laneLimit)
        val indexed = events.filter { it.mediaTimeMs != null && it.mediaTimeMs >= 0 && DanmakuPresentation.text(it, style) != null }
            .sortedBy { it.mediaTimeMs }
        val content = when (format) {
            DanmakuSubtitleFormat.JSONL, DanmakuSubtitleFormat.COVERAGE -> error("This format is handled separately")
            DanmakuSubtitleFormat.SRT -> indexed.mapIndexed { index, event ->
                val start = event.mediaTimeMs ?: 0L
                "${index + 1}\n${srtTime(start)} --> ${srtTime(start + 3_000)}\n${DanmakuPresentation.text(event, style)}\n"
            }.joinToString("\n")
            DanmakuSubtitleFormat.ASS -> assHeader(style) + ScrollingDanmakuTimeline(indexed.map { event ->
                val text = requireNotNull(DanmakuPresentation.text(event, style))
                // ASS has no font measurement API; reserve a full glyph width for safe spacing.
                TimedBullet(text, requireNotNull(event.mediaTimeMs), text.codePointCount(0, text.length) * style.fontSizeSp * 2.5f + 16f)
            }, 1920f, lanes, gap = 48f, travelMs = (6500 / style.speed).toLong()).bullets.joinToString("\n") { bullet ->
                val y = 60 + bullet.lane * lineHeight.toInt()
                val movement = "{\\an7\\move(1920,$y,${-bullet.width.toInt()},$y)}"
                "Dialogue: 0,${assTime(bullet.startMs)},${assTime(bullet.endMs)},Default,,0,0,0,,$movement${escapeAss(bullet.text)}"
            }
        }
        target.writeText(content)
    }

    private fun srtTime(ms: Long) = String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1_000) % 60, ms % 1_000)
    private fun assTime(ms: Long) = String.format(Locale.US, "%d:%02d:%02d.%02d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1_000) % 60, (ms % 1_000) / 10)
    private fun escapeAss(value: String) = value.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\r", "").replace("\n", "\\N")
    private fun assHeader(style: DanmakuAppearance): String {
        val rgb = style.colorRgb
        val bgr = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or (rgb ushr 16)
        val alpha = ((1 - style.opacity) * 255).toInt()
        val color = String.format(Locale.US, "&H%02X%06X", alpha, bgr)
        val outline = String.format(Locale.US, "&H%02X000000", alpha)
        val size = (style.fontSizeSp * 2.5f).toInt()
        return "[Script Info]\nScriptType: v4.00+\nPlayResX: 1920\nPlayResY: 1080\nWrapStyle: 2\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Noto Sans CJK SC,$size,$color,$color,$outline,&H80000000,-1,0,0,0,100,100,0,0,1,2,0,7,0,0,0,1\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    }
}
