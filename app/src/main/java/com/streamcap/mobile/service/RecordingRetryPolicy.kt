package com.streamcap.mobile.service

import com.streamcap.mobile.recording.engine.RecorderState

internal data class RecordingTerminalDecision(
    val disconnectPaused: Boolean,
    val paused: Boolean,
    val liveDisconnect: Boolean,
    val shouldRetry: Boolean,
    val publishBeforeRetry: Boolean,
)

/** Keeps terminal-state and retry precedence independent from service side effects. */
internal object RecordingRetryPolicy {
    const val MAX_START_RETRIES = 4

    fun shouldRetryStartup(
        stopRequested: Boolean,
        pauseRequested: Boolean,
        stopAtMillis: Long?,
        nowMillis: Long,
        retryCount: Int,
        scheduledMonitoringEnabled: Boolean,
        roomEnabled: Boolean,
    ): Boolean = !stopRequested && !pauseRequested &&
        (stopAtMillis == null || stopAtMillis > nowMillis) &&
        (retryCount < MAX_START_RETRIES || scheduledMonitoringEnabled) && roomEnabled

    fun terminal(
        state: RecorderState,
        bytesWritten: Long,
        retryExhausted: Boolean,
        liveEnded: Boolean,
        stopRequested: Boolean,
        pauseRequested: Boolean,
        stopAtMillis: Long?,
        nowMillis: Long,
        roomEnabled: Boolean,
        scheduledMonitoringEnabled: Boolean,
        retryCount: Int,
        hlsFallback: Boolean,
    ): RecordingTerminalDecision {
        val intentionalStop = state == RecorderState.STOPPED || stopRequested || pauseRequested
        val stopTimeNotReached = stopAtMillis == null || stopAtMillis > nowMillis
        val disconnectPaused = retryExhausted && !liveEnded && !hlsFallback
        val shouldRetry = state == RecorderState.FAILED &&
            (!retryExhausted || hlsFallback) &&
            !liveEnded && !stopRequested && !pauseRequested && stopTimeNotReached &&
            roomEnabled && (retryCount < MAX_START_RETRIES || scheduledMonitoringEnabled)
        val liveDisconnect = state == RecorderState.COMPLETED && bytesWritten > 0L &&
            !intentionalStop && !liveEnded && stopTimeNotReached && roomEnabled

        return RecordingTerminalDecision(
            disconnectPaused = disconnectPaused,
            paused = pauseRequested || disconnectPaused,
            liveDisconnect = liveDisconnect,
            shouldRetry = shouldRetry,
            publishBeforeRetry = (shouldRetry || liveDisconnect || hlsFallback) && bytesWritten > 0L,
        )
    }
}
