package com.streamcap.mobile.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class RecordingNameContext(
    val platform: String,
    val anchorName: String,
    val title: String?,
    val time: LocalDateTime,
)

object RecordingNameFormatter {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val invalidCharacters = Regex("[\\\\/:*?\"<>|]")
    private val repeatedSeparators = Regex("[_ -]{2,}")
    private val emoji = Regex("[\\p{So}\\p{Sk}]")

    fun format(
        template: String,
        includeTitle: Boolean,
        removeEmoji: Boolean,
        context: RecordingNameContext,
    ): String {
        val effectiveTemplate = template.trim().ifBlank { "{anchor_name}_{time}" }
        var name = effectiveTemplate
            .replace("{platform}", context.platform)
            .replace("{anchor_name}", context.anchorName)
            .replace("{anchor}", context.anchorName)
            .replace("{title}", if (includeTitle) context.title.orEmpty() else "")
            .replace("{time}", context.time.format(timeFormatter))
            .replace(Regex("\\{[^{}]+\\}"), "")
        if (removeEmoji) name = name.replace(emoji, "")
        return name
            .replace(invalidCharacters, "_")
            .replace(repeatedSeparators, "_")
            .trim(' ', '_', '-', '.')
            .take(120)
            .ifBlank { "recording_${context.time.format(timeFormatter)}" }
    }
}
