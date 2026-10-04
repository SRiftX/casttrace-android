package com.streamcap.mobile.ui.player

internal object PlaybackPages {
    private const val HOUR_MS = 3_600_000L

    fun <T> group(parts: List<T>, durationsMs: List<Long>): List<List<T>> {
        val pages = mutableListOf<MutableList<T>>()
        var pageDuration = 0L
        parts.forEachIndexed { index, part ->
            val duration = durationsMs.getOrNull(index)?.takeIf { it > 0L } ?: HOUR_MS
            if (pages.isEmpty() || (pages.last().isNotEmpty() && pageDuration + duration > HOUR_MS)) {
                pages += mutableListOf<T>()
                pageDuration = 0L
            }
            pages.last() += part
            pageDuration += duration
        }
        return pages
    }
}
