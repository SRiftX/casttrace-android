package com.streamcap.mobile.recording

import com.streamcap.mobile.recording.flv.flvTimelineOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class FlvTimelineTest {
    @Test fun refreshedYyCdnWithHoursOfUptimeDoesNotAddHoursToRecording() {
        val raw = 55_000_000L
        val offset = flvTimelineOffset(raw, 0, 199_000, true)
        assertEquals(199_001L, raw + offset)
        assertEquals(199_041L, raw + 40 + flvTimelineOffset(raw + 40, offset, 199_001, false))
    }
    @Test fun backwardsResetAndNormalReconnectPreserveContinuity() {
        assertEquals(50_001L, 1_000 + flvTimelineOffset(1_000, 0, 50_000, true))
        assertEquals(0L, flvTimelineOffset(50_040, 0, 50_000, true))
        assertEquals(0L, flvTimelineOffset(500_000, 0, Long.MIN_VALUE, false))
    }
    @Test fun forwardGapWithinConnectionIsNotHidden() {
        assertEquals(0L, flvTimelineOffset(500_000, 0, 50_000, false))
        assertEquals(0L, flvTimelineOffset(500_000, 0, 50_000, false, 450_000))
    }
    @Test fun yyCanChangeVideoEpochWithoutReopeningTheHttpConnection() {
        val offset = flvTimelineOffset(57_850_000, 0, 574_000, false, 40)
        assertEquals(574_001L, 57_850_000 + offset)
        assertEquals(574_041L, 57_850_040 + flvTimelineOffset(57_850_040, offset, 574_001, false, 40))
    }
}
