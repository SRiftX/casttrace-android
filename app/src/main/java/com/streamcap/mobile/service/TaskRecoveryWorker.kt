package com.streamcap.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcap.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.flow.first

class TaskRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = AppPreferences(applicationContext).settings.first()
        // Always repair/publish interrupted media, but only resume live monitoring when
        // the user explicitly enabled boot recovery.
        if (settings.resumeAfterBoot && settings.globalAutoRecordEnabled && settings.backgroundMonitoringEnabled) RecordingService.monitor(applicationContext)
        else RecordingService.recover(applicationContext)
        return Result.success()
    }
}
