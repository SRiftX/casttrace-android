package com.streamcap.mobile.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

class RecordingNameFormatterTest {
    private val context = RecordingNameContext(
        platform = "虎牙直播",
        anchorName = "主播/A",
        title = "晚间场😀",
        time = LocalDateTime.of(2026, 8, 10, 14, 30, 5),
    )

    @Test
    fun expandsTemplateAndSanitizesFileName() {
        val result = RecordingNameFormatter.format(
            "{platform}_{anchor_name}_{title}_{time}",
            includeTitle = true,
            removeEmoji = true,
            context = context,
        )

        assertEquals("虎牙直播_主播_A_晚间场_20260810_143005", result)
    }

    @Test
    fun removesTitleWhenDisabled() {
        val result = RecordingNameFormatter.format(
            "{anchor_name}_{title}_{time}",
            includeTitle = false,
            removeEmoji = false,
            context = context,
        )

        assertFalse(result.contains("晚间场"))
        assertEquals("主播_A_20260810_143005", result)
    }

    @Test
    fun removesUnknownPlaceholdersWithoutBreakingPublishing() {
        val result = RecordingNameFormatter.format(
            "{anchor_name}_{unknown}_{time}",
            includeTitle = true,
            removeEmoji = false,
            context = context,
        )

        assertFalse(result.contains("{"))
        assertFalse(result.contains("}"))
        assertEquals("主播_A_20260810_143005", result)
    }
}
