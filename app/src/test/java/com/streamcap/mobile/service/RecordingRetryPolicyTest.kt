package com.streamcap.mobile.service

import com.streamcap.mobile.recording.engine.RecorderState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRetryPolicyTest {
    @Test
    fun explicitStopAndPauseBlockStartupRetries() {
        assertFalse(startupRetry(stopRequested = true))
        assertFalse(startupRetry(pauseRequested = true))
        assertFalse(startupRetry(stopAtMillis = 99L))
        assertFalse(startupRetry(roomEnabled = false))
    }

    @Test
    fun scheduledMonitoringExtendsRetryLimitButDoesNotOverrideUserCommands() {
        assertFalse(startupRetry(retryCount = RecordingRetryPolicy.MAX_START_RETRIES))
        assertTrue(startupRetry(
            retryCount = RecordingRetryPolicy.MAX_START_RETRIES,
            scheduledMonitoringEnabled = true,
        ))
        assertFalse(startupRetry(
            stopRequested = true,
            retryCount = RecordingRetryPolicy.MAX_START_RETRIES,
            scheduledMonitoringEnabled = true,
        ))
    }

    @Test
    fun terminalPauseAndStopCannotBeReplacedByRetry() {
        val paused = terminal(pauseRequested = true)
        val stopped = terminal(stopRequested = true)

        assertTrue(paused.paused)
        assertFalse(paused.shouldRetry)
        assertFalse(stopped.shouldRetry)
    }

    @Test
    fun hlsFallbackAllowsRetryAfterFlvRetryExhaustionAndPublishesWrittenMediaFirst() {
        val decision = terminal(
            state = RecorderState.FAILED,
            bytesWritten = 64L,
            retryExhausted = true,
            hlsFallback = true,
        )

        assertTrue(decision.shouldRetry)
        assertTrue(decision.publishBeforeRetry)
        assertFalse(decision.disconnectPaused)
    }

    @Test
    fun retryExhaustionPausesWhenNoFallbackExists() {
        val decision = terminal(
            state = RecorderState.FAILED,
            retryExhausted = true,
        )

        assertTrue(decision.disconnectPaused)
        assertTrue(decision.paused)
        assertFalse(decision.shouldRetry)
    }

    private fun startupRetry(
        stopRequested: Boolean = false,
        pauseRequested: Boolean = false,
        stopAtMillis: Long? = null,
        retryCount: Int = 0,
        scheduledMonitoringEnabled: Boolean = false,
        roomEnabled: Boolean = true,
    ) = RecordingRetryPolicy.shouldRetryStartup(
        stopRequested = stopRequested,
        pauseRequested = pauseRequested,
        stopAtMillis = stopAtMillis,
        nowMillis = 100L,
        retryCount = retryCount,
        scheduledMonitoringEnabled = scheduledMonitoringEnabled,
        roomEnabled = roomEnabled,
    )

    private fun terminal(
        state: RecorderState = RecorderState.FAILED,
        bytesWritten: Long = 0L,
        retryExhausted: Boolean = false,
        liveEnded: Boolean = false,
        stopRequested: Boolean = false,
        pauseRequested: Boolean = false,
        stopAtMillis: Long? = null,
        roomEnabled: Boolean = true,
        scheduledMonitoringEnabled: Boolean = false,
        retryCount: Int = 0,
        hlsFallback: Boolean = false,
    ) = RecordingRetryPolicy.terminal(
        state = state,
        bytesWritten = bytesWritten,
        retryExhausted = retryExhausted,
        liveEnded = liveEnded,
        stopRequested = stopRequested,
        pauseRequested = pauseRequested,
        stopAtMillis = stopAtMillis,
        nowMillis = 100L,
        roomEnabled = roomEnabled,
        scheduledMonitoringEnabled = scheduledMonitoringEnabled,
        retryCount = retryCount,
        hlsFallback = hlsFallback,
    )
}
