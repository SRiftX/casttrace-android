package com.streamcap.mobile.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPreviewPolicyTest {
    @Test fun largeActiveTailNeverBlocksPublishedPlayback() {
        val large = 512L * 1024 * 1024
        assertEquals(RecordingPreviewPolicy.TailAction.DEFER,
            RecordingPreviewPolicy.activeTailAction(large, hasPublishedPart = true))
        assertEquals(RecordingPreviewPolicy.TailAction.DIRECT,
            RecordingPreviewPolicy.activeTailAction(large, hasPublishedPart = false))
        assertEquals(RecordingPreviewPolicy.TailAction.SNAPSHOT,
            RecordingPreviewPolicy.activeTailAction(8L * 1024 * 1024, hasPublishedPart = true))
    }
}
