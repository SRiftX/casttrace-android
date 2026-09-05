package com.streamcap.mobile.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.LiveStatus
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class RoomMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as StreamCapApplication).container.repository
        val rooms = repository.monitoredRooms()
        rooms.forEachIndexed { index, room ->
            if (room.taskStatus == com.streamcap.mobile.domain.model.TaskStatus.PAUSED) {
                if (index < rooms.lastIndex) delay(500)
                return@forEachIndexed
            }
            runCatching { repository.refresh(room.id) }
                .onSuccess { status ->
                    if (status == LiveStatus.LIVE) RecordingService.start(applicationContext, room.id)
                }
                .onFailure { error ->
                    repository.log(room.id, null, "ERROR", "AUTO_MONITOR_FAILED", error.message ?: "自动检查开播失败")
                }
            if (index < rooms.lastIndex) delay(500)
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "streamcap-room-monitor"
        private const val IMMEDIATE_NAME = "streamcap-room-monitor-now"

        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RoomMonitorWorker>(15, TimeUnit.MINUTES).build(),
            )
            manager.enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RoomMonitorWorker>().build(),
            )
        }

        fun cancelLegacySchedule(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_NAME)
                cancelUniqueWork(IMMEDIATE_NAME)
            }
        }
    }
}
