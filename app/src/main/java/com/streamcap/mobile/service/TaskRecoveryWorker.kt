package com.streamcap.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TaskRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // RecordingService.monitor performs recovery before scanning rooms, including
        // sessions left by a force-stop. Keep a single recovery entry point at boot.
        RecordingService.monitor(applicationContext)
        return Result.success()
    }
}
