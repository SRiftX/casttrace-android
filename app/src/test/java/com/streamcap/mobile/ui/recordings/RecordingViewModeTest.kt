package com.streamcap.mobile.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingViewModeTest {
    @Test
    fun `missing or invalid value defaults to compact`() {
        assertEquals(RecordingViewMode.COMPACT, RecordingViewMode.fromStored(null))
        assertEquals(RecordingViewMode.COMPACT, RecordingViewMode.fromStored("unknown"))
    }

    @Test
    fun `detailed value restores detailed mode`() {
        assertEquals(RecordingViewMode.DETAILED, RecordingViewMode.fromStored("detailed"))
    }

    @Test
    fun `labels describe the information hierarchy`() {
        assertEquals("摘要", RecordingViewMode.COMPACT.label)
        assertEquals("详情", RecordingViewMode.DETAILED.label)
    }
}
