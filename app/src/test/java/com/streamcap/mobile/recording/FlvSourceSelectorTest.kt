package com.streamcap.mobile.recording

import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.flv.FlvSourceSelector
import org.junit.Assert.*
import org.junit.Test

class FlvSourceSelectorTest {
    @Test fun keepsProductiveCdnAndSwitchesOnlyAfterEmptyResponse() {
        val old = StreamSource("https://one.test/live.flv?token=old", StreamType.FLV, "原画")
        val refreshed = old.copy(url = "https://one.test/live.flv?token=new")
        val alternate = old.copy(url = "https://two.test/live.flv?token=new")
        assertEquals(refreshed, FlvSourceSelector.select(listOf(alternate, refreshed), old, true))
        assertEquals(alternate, FlvSourceSelector.select(listOf(refreshed, alternate), old, false))
        assertEquals(refreshed, FlvSourceSelector.select(listOf(refreshed), old, true))
        assertEquals(old, FlvSourceSelector.select(listOf(old, alternate), old, true))
        assertNull(FlvSourceSelector.select(listOf(old), old, false))
        assertNull(FlvSourceSelector.select(emptyList(), old, false))
    }
}
