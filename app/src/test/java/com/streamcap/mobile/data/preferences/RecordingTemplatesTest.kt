package com.streamcap.mobile.data.preferences

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RecordingTemplatesTest {
    @Test
    fun `templates round trip reserved characters and unicode`() {
        val input = listOf(
            RecordingTemplate("客厅 | 夜间", "MP4", "原画", 45, 900),
            RecordingTemplate("A|B", "MKV", "1080p", 120, 1_800),
        )

        val decoded = decodeTemplates(encodeTemplates(input))

        assertEquals(input, decoded)
    }

    @Test
    fun `invalid template rows are ignored and invalid segment uses default`() {
        val raw = listOf(
            "missing|fields",
            "name|MP4|原画|not-a-number|900",
            "valid|MKV|720p|30|not-a-number",
        ).joinToString("\n")

        val decoded = decodeTemplates(raw)

        assertEquals(1, decoded.size)
        assertEquals("valid", decoded.single().name)
        assertEquals(1_800, decoded.single().segmentSeconds)
        assertTrue(decoded.single().intervalSeconds == 30)
    }
}

