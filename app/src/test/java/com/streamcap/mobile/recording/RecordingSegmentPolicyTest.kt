package com.streamcap.mobile.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSegmentPolicyTest {
    @Test fun disabledSegmentationStillSavesHourly() {
        assertEquals(3_600, RecordingSegmentPolicy.effectiveSeconds(false, 1_800))
    }

    @Test fun longSegmentIsLimitedToOneHour() {
        assertEquals(3_600, RecordingSegmentPolicy.effectiveSeconds(true, 10_800))
    }

    @Test fun shorterSegmentKeepsRequestedDuration() {
        assertEquals(1_800, RecordingSegmentPolicy.effectiveSeconds(true, 1_800))
    }
}
