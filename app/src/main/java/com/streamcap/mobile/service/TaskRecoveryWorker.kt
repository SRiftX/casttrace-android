package com.streamcap.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.app.StreamCapApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException

class TaskRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = AppPreferences(applicationContext).settings.first()
        // Always repair/publish interrupted media, but only resume live monitoring when
        // the user explicitly enabled boot recovery.
        return try {
            val hasReminders = (applicationContext as StreamCapApplication).container.repository.hasEnabledReminders()
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
        (applicationContext as StreamCapApplication).container.repository.log(null, null, "WARN",
            "BACKGROUND_START_BLOCKED", "系统拒绝启动恢复服务：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
        return Result.retry()
    }
}
