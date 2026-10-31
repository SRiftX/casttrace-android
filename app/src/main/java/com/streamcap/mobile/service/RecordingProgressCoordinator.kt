package com.streamcap.mobile.service

import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.RecordingProgress
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.storage.RecordingStorage
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/** Persists recorder progress and publishes closed checkpoints until a terminal state arrives. */
@ServiceScoped
class RecordingProgressCoordinator @Inject constructor(
    private val repository: StreamCapRepository,
    private val recordingStorage: RecordingStorage,
    private val publicationCoordinator: RecordingPublicationCoordinator,
    private val runtimeState: RecordingRuntimeState,
) {
    internal suspend fun awaitTerminal(
        recording: ActiveRecording,
        notifications: RecordingNotifications,
    ): RecordingProgress {
        var lastPersistAt = 0L
        var lastNotificationAt = 0L
        var lastRoomStateAt = 0L
        var lastRoomState: RecorderState? = null
        return recording.recorder.observe(recording.handle)
            .onEach { progress ->
                val now = System.currentTimeMillis()
                if (progress.state == RecorderState.RECORDING &&
                    recordingStorage.recordingRoot().usableSpace < MIN_RUNTIME_FREE_BYTES
                ) {
                    repository.log(recording.roomId, recording.sessionId, "WARN", "STORAGE_LOW", "可用空间低于 128 MB，正在停止并保存已有内容")
                    recording.stopReason = StopReason.STORAGE_LOW
                    recording.recorder.stop(recording.handle, StopReason.STORAGE_LOW)
                }
                if (recording.notificationsEnabled && now - lastNotificationAt >= NOTIFICATION_INTERVAL_MILLIS) {
                    notifications.notifyRoom(
                        recording.roomId,
                        notifications.recordingNotification(
                            recording.roomId,
                            recording.roomName,
                            if (progress.state == RecorderState.RECONNECTING) "断流重试中 · 已录制 ${formatBytes(progress.bytesWritten)}"
                            else "已录制 ${formatBytes(progress.bytesWritten)} · ${formatDuration(progress.durationSeconds)}",
                            ongoing = progress.state == RecorderState.STARTING ||
                                progress.state == RecorderState.RECORDING ||
                                progress.state == RecorderState.RECONNECTING,
                        ),
                    )
                    lastNotificationAt = now
                }
                if (progress.state !in TERMINAL_STATES && !runtimeState.pauseRequested.contains(recording.roomId)) {
                    publicationCoordinator.checkpointCompletedParts(recording, progress.completedOutputFiles)
                }
                if (progress.state != lastRoomState && now - lastRoomStateAt >= STATE_UPDATE_INTERVAL_MILLIS &&
                    (progress.state == RecorderState.RECONNECTING || progress.state == RecorderState.RECORDING)
                ) {
                    repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, progress.state.toTaskStatus())
                    lastRoomState = progress.state
                    lastRoomStateAt = now
                }
                if (now - lastPersistAt >= STATE_UPDATE_INTERVAL_MILLIS) {
                    repository.session(recording.sessionId)?.let { session ->
                        repository.updateSession(
                            session.copy(
                                status = progress.state.toTaskStatus(),
                                totalBytes = progress.bytesWritten,
                                durationSeconds = progress.durationSeconds,
                                errorMessage = progress.errorMessage,
                            ),
                        )
                    }
                    lastPersistAt = now
                }
            }
            .first { it.state in TERMINAL_STATES }
    }

    private fun RecorderState.toTaskStatus(): TaskStatus = when (this) {
        RecorderState.STARTING -> TaskStatus.STARTING
        RecorderState.RECORDING -> TaskStatus.RECORDING
        RecorderState.RECONNECTING -> TaskStatus.RECONNECTING
        RecorderState.COMPLETED, RecorderState.STOPPED -> TaskStatus.FINALIZING
        RecorderState.FAILED -> TaskStatus.FAILED
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1_024.0)
    }

    private fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(
        seconds / 3_600,
        (seconds % 3_600) / 60,
        seconds % 60,
    )

    private companion object {
        const val MIN_RUNTIME_FREE_BYTES = 128L * 1_048_576L
        const val NOTIFICATION_INTERVAL_MILLIS = 5_000L
        const val STATE_UPDATE_INTERVAL_MILLIS = 5_000L
        val TERMINAL_STATES = setOf(RecorderState.COMPLETED, RecorderState.STOPPED, RecorderState.FAILED)
    }
}
