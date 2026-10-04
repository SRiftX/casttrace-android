package com.streamcap.mobile.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackPagesTest {
    @Test fun groupsTwoHalfHourSegmentsPerPage() {
        assertEquals(listOf(listOf(1, 2), listOf(3)),
            PlaybackPages.group(listOf(1, 2, 3), listOf(1_800_000, 1_800_000, 1_800_000)))
    }

    @Test fun unknownDurationGetsItsOwnPage() {
        assertEquals(listOf(listOf(1), listOf(2)),
            PlaybackPages.group(listOf(1, 2), listOf(0, 1_800_000)))
    }
}
