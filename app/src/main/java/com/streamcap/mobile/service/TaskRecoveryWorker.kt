package com.streamcap.mobile.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

@HiltWorker
class TaskRecoveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = preferences.settings.first()
        // Always repair/publish interrupted media, but only resume live monitoring when
        // the user explicitly enabled boot recovery.
        return try {
            val hasReminders = repository.hasEnabledReminders()
            if (hasReminders || settings.resumeAfterBoot && settings.globalAutoRecordEnabled && settings.backgroundMonitoringEnabled) RecordingService.monitor(applicationContext)
            else RecordingService.recover(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (restricted: IllegalStateException) {
            blocked(restricted)
        } catch (restricted: SecurityException) {
            blocked(restricted)
        }
    }

    private suspend fun blocked(error: Exception): Result {
        BackgroundMonitorStatus.blocked(applicationContext, error.message ?: error.javaClass.simpleName)
        repository.log(null, null, "WARN",
            "BACKGROUND_START_BLOCKED", "系统拒绝启动恢复服务：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        return Result.retry()
    }
}
