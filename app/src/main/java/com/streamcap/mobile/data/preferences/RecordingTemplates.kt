package com.streamcap.mobile.data.preferences

import com.streamcap.mobile.domain.model.RoomRecordingOptions
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RecordingTemplate(
    val name: String,
    val format: String,
    val quality: String,
    val intervalSeconds: Int,
    val segmentSeconds: Int = 1_800,
) {
    fun options(base: RoomRecordingOptions = RoomRecordingOptions()): RoomRecordingOptions = base.copy(
        outputFormat = format, quality = quality, checkIntervalSeconds = intervalSeconds, segmentSeconds = segmentSeconds,
    )
}

internal fun encodeTemplates(items: List<RecordingTemplate>): String = items.joinToString("\n") {
    listOf(it.name, it.format, it.quality, it.intervalSeconds, it.segmentSeconds)
        .joinToString("|") { value -> encodeTemplateField(value.toString()) }
}

internal fun decodeTemplates(raw: String): List<RecordingTemplate> = raw.lineSequence().mapNotNull { line ->
    val fields = runCatching { line.split('|').map(::decodeTemplateField) }.getOrNull()
        ?: return@mapNotNull null
    if (fields.size != 5) return@mapNotNull null
    RecordingTemplate(fields[0], fields[1], fields[2], fields[3].toIntOrNull() ?: return@mapNotNull null, fields[4].toIntOrNull() ?: 1_800)
}.toList()

private fun encodeTemplateField(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun decodeTemplateField(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
