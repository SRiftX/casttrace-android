package com.streamcap.mobile.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.text.Normalizer
import java.security.MessageDigest

data class RecordingNameContext(
    val platform: String,
    val anchorName: String,
    val title: String?,
    val time: LocalDateTime,
)

object RecordingNameFormatter {
    fun safeComponent(value: String): String {
        var name = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[\\x00-\\x1f\\\\/:*?\"<>|]"), "_").trim(' ', '.')
            .ifBlank { "recording" }
        if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(name)) name = "_$name"
        if (name.toByteArray(Charsets.UTF_8).size <= 160) return name
        val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        val prefix = StringBuilder()
        var bytes = 0
        for (codePoint in name.codePoints().toArray()) {
            val next = String(Character.toChars(codePoint))
            val size = next.toByteArray(Charsets.UTF_8).size
            if (bytes + size > 140) break
            prefix.append(next)
            bytes += size
        }
        return "${prefix.toString().trimEnd(' ', '.')}_$hash"
    }
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
        return safeComponent(name
            .replace(invalidCharacters, "_")
            .replace(repeatedSeparators, "_")
            .trim(' ', '_', '-', '.')
            .ifBlank { "recording_${context.time.format(timeFormatter)}" })
    }
}
