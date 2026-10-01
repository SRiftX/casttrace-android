package com.streamcap.mobile.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime

class RecordingNameFormatterTest {
    @org.junit.Test fun safeNamesHandleWindowsReservedUnicodeAndByteLength() {
        org.junit.Assert.assertEquals("_CON", RecordingNameFormatter.safeComponent("CON. "))
        org.junit.Assert.assertEquals("é", RecordingNameFormatter.safeComponent("e\u0301"))
        val name = RecordingNameFormatter.safeComponent("视频😀".repeat(100))
        org.junit.Assert.assertTrue(name.toByteArray(Charsets.UTF_8).size <= 160)
        org.junit.Assert.assertFalse(name.contains('\uFFFD'))
        org.junit.Assert.assertNotEquals(name, RecordingNameFormatter.safeComponent("视频😀".repeat(99) + "different"))
    }
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
