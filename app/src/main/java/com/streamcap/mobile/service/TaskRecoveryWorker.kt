package com.streamcap.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamcap.mobile.app.StreamCapApplication
import kotlinx.coroutines.flow.first

class TaskRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StreamCapApplication).container
        if (container.preferences.settings.first().resumeAfterBoot) {
            RecordingService.recover(applicationContext)
        }
        RecordingService.monitor(applicationContext)
        return Result.success()
    }
}
