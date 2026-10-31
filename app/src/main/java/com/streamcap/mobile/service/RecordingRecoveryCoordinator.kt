package com.streamcap.mobile.service

import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlinx.coroutines.sync.withLock

/** Repairs and publishes media left by a previous service process. */
@ServiceScoped
class RecordingRecoveryCoordinator @Inject constructor(
    private val repository: StreamCapRepository,
    private val publicationCoordinator: RecordingPublicationCoordinator,
    private val runtimeState: RecordingRuntimeState,
) {
    suspend fun recover(
        sessionId: Long?,
        serviceCreatedAt: Long,
        startRecording: (roomId: Long, stopAtMillis: Long?) -> Unit,
    ) = runtimeState.recoveryMutex.withLock {
        // Only sessions left by a previous service instance need crash recovery.
        // A second ACTION_MONITOR must never remux an actively written file.
        val sessions = (repository.activeSessions() + repository.failedPublications()).filter {
            (sessionId == null || it.id == sessionId) &&
                (it.status == TaskStatus.FAILED || (it.startedAt ?: 0L) < serviceCreatedAt) &&
                !runtimeState.active.containsKey(it.roomId) && !runtimeState.starting.contains(it.roomId)
        }
        sessions.forEach { session ->
            val recovered = runCatching { publicationCoordinator.recoverInterruptedSession(session) }
                .getOrElse { failure ->
                    val latest = repository.session(session.id) ?: session
                    repository.updateSession(latest.copy(
                        status = TaskStatus.FAILED,
                        endedAt = System.currentTimeMillis(),
                        errorMessage = "应用进程中断，恢复失败：${failure.message}",
                    ))
                    false
                }
            if (!recovered) {
                val latest = repository.session(session.id) ?: session
                if (latest.status != TaskStatus.FAILED) repository.updateSession(latest.copy(
                    status = TaskStatus.FAILED,
                    endedAt = System.currentTimeMillis(),
                    errorMessage = "应用进程中断，未发现可恢复的媒体文件；已有视频不会被删除",
                ))
            }
            repository.finishInterruptedStatus(session.roomId, if (recovered) TaskStatus.PAUSED else TaskStatus.FAILED)
            repository.log(
                session.roomId,
                session.id,
                if (recovered) "WARN" else "ERROR",
                "INTERRUPTED_RECORDING_RECOVERED",
                if (recovered) "应用进程中断，已自动封装并保存已录内容" else "应用进程中断，未发现可恢复的媒体文件",
            )
            val remainingStopAt = session.stopAtMillis?.takeIf { it > System.currentTimeMillis() }
            val room = repository.room(session.roomId)
            if (session.status != TaskStatus.FAILED && room?.enabled == true && room.autoRecord &&
                (session.stopAtMillis == null || remainingStopAt != null)) {
                startRecording(session.roomId, remainingStopAt)
            }
        }
        // The caller may still be preparing the monitor and starting recordings.
        // Stopping here cancels that startup and leaves persisted STARTING rows.
    }
}
