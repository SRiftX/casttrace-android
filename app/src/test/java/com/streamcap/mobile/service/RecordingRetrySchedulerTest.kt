package com.streamcap.mobile.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingRetrySchedulerTest {
    @Test fun retryWaitsUntilDeadlineAndReleasesReservationBeforeStarting() = runTest {
        val scheduler = RecordingRetryScheduler(this)
        var starts = 0
        scheduler.schedule(1, 0) {
            assertFalse(scheduler.isPending(1))
            starts++
        }
        runCurrent()
        advanceTimeBy(1999)
        assertTrue(scheduler.isPending(1))
        assertEquals(0, starts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, starts)
        assertFalse(scheduler.hasPending())
    }

    @Test fun pauseOrStopCancelsDelayedRestart() = runTest {
        val scheduler = RecordingRetryScheduler(this)
        var starts = 0
        scheduler.schedule(1, 4) { starts++ }
        runCurrent()
        advanceTimeBy(10000)
        scheduler.cancel(1)
        advanceTimeBy(60000)
        runCurrent()
        assertEquals(0, starts)
        assertFalse(scheduler.hasPending())
    }

    @Test fun replacementDoesNotLetOldTimerStartOrRemoveNewTimer() = runTest {
        val scheduler = RecordingRetryScheduler(this)
        var starts = 0
        scheduler.schedule(1, 0) { fail("Old timer fired") }
        runCurrent()
        advanceTimeBy(1000)
        scheduler.schedule(1, 1) { starts++ }
        runCurrent()
        advanceTimeBy(1000)
        assertTrue(scheduler.isPending(1))
        assertEquals(0, starts)
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test fun cancellingOneRoomKeepsOtherRoomRetry() = runTest {
        val scheduler = RecordingRetryScheduler(this)
        var starts = 0
        scheduler.schedule(1, 0) { fail("Cancelled room restarted") }
        scheduler.schedule(2, 0) { starts++ }
        scheduler.cancel(1)
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test fun backoffIsCappedWithoutOverflow() {
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (0..5).map(RecordingRetryScheduler::delayMillis))
        assertEquals(2000L, RecordingRetryScheduler.delayMillis(-1))
        assertEquals(30000L, RecordingRetryScheduler.delayMillis(Int.MAX_VALUE))
    }
}
