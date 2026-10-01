package com.streamcap.mobile.danmaku

import java.io.File
import java.util.Locale
import org.json.JSONObject
import com.streamcap.mobile.data.preferences.DanmakuAppearance

enum class DanmakuSubtitleFormat { SRT, ASS, JSONL }

object DanmakuSubtitleExporter {
    fun export(events: List<DanmakuEvent>, target: File, format: DanmakuSubtitleFormat,
               appearance: DanmakuAppearance = DanmakuAppearance()) {
        val style = appearance.normalized()
        val lineHeight = style.fontSizeSp * 2.5f + 16
        val lanes = ((1080 * style.area - 60) / lineHeight).toInt().coerceIn(1, style.laneLimit)
        val indexed = events.filter { it.mediaTimeMs != null && it.mediaTimeMs >= 0 }.sortedBy { it.mediaTimeMs }
        target.parentFile?.mkdirs()
        val content = when (format) {
            DanmakuSubtitleFormat.JSONL -> events.joinToString("\n") { event -> JSONObject().apply {
                put("sessionId", event.sessionId); put("platform", event.platform); put("eventId", event.eventId)
                put("mediaTimeMs", event.mediaTimeMs); put("timeQuality", event.timeQuality)
                put("userName", event.userName); put("text", event.text); put("rawType", event.rawType)
            }.toString() }
            DanmakuSubtitleFormat.SRT -> indexed.mapIndexed { index, event ->
                val start = event.mediaTimeMs ?: 0L
                "${index + 1}\n${srtTime(start)} --> ${srtTime(start + 3_000)}\n${event.text}\n"
            }.joinToString("\n")
            DanmakuSubtitleFormat.ASS -> assHeader(style) + ScrollingDanmakuTimeline(indexed.map { event ->
                val singleLine = event.text.replace(Regex("[\\r\\n\\t]+"), " ")
                val text = if (singleLine.codePointCount(0, singleLine.length) > 70) {
                    singleLine.substring(0, singleLine.offsetByCodePoints(0, 69)) + "..."
                } else singleLine
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
