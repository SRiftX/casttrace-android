package com.streamcap.mobile.recording

import com.streamcap.mobile.recording.flv.ReconnectWindow
import org.junit.Assert.*
import org.junit.Test

class ReplayWindowTest {
    @Test fun repeatedOutagesEachReceiveFullGraceAfterNewMedia() {
        val window = ReconnectWindow()
        repeat(3) { outage ->
            val start = outage * 200_000_000_000L
            repeat(10) { assertTrue(window.shouldContinue(false, true, start + it * 8_000_000_000L)) }
            assertTrue(window.shouldContinue(true, true, start + 80_000_000_000L))
        }
    }
    @Test fun sustainedOutageExitsAtGraceBoundaryInsteadOfWaitingForever() {
        val window = ReconnectWindow()
        assertTrue(window.shouldContinue(false, true, 0))
        assertTrue(window.shouldContinue(false, true, 89_000_000_000L))
        assertFalse(window.shouldContinue(false, true, 90_000_000_000L))
    }
    @Test fun initialEmptyStreamStillFailsFast() {
        val window = ReconnectWindow()
        assertTrue(window.shouldContinue(false, false, 0))
        assertTrue(window.shouldContinue(false, false, 1))
        assertFalse(window.shouldContinue(false, false, 2))
    }
    @Test fun repeatedShortResponsesWithNewMediaRemainHealthy() {
        val window = ReconnectWindow()
        repeat(120) { assertTrue(window.shouldContinue(true, true, it * 1_500_000_000L)) }
        assertTrue(window.shouldContinue(false, true, 181_000_000_000L))
        assertTrue(window.shouldContinue(false, true, 271_000_000_000L))
        assertFalse(window.shouldContinue(false, true, 361_000_000_000L))
    }
}
