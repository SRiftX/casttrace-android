package com.streamcap.mobile.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.diagnostics.DiagnosticFailure
import com.streamcap.mobile.notification.LiveReminderNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import com.streamcap.mobile.data.preferences.AppPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RoomMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        performWork()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        repository.log(null, null, "ERROR", "BACKGROUND_WORKER_FAILED", DiagnosticFailure.describe(error))
        Result.retry()
    }

    private suspend fun performWork(): Result {
        val settings = preferences.settings.first()
        val scheduledBoot = applicationContext.getSharedPreferences(RECOVERY_POLICY, Context.MODE_PRIVATE)
            .getInt("scheduledBoot", -1)
        val reminders = repository.enabledReminders()
        if (!BackgroundMonitoringPolicy.mayRestore(settings, scheduledBoot, bootCount(applicationContext)) && reminders.isEmpty()) return Result.success()
        val rooms = if (settings.globalAutoRecordEnabled && settings.backgroundMonitoringEnabled) {
            repository.monitoredRooms()
        } else emptyList()
        if (rooms.isEmpty() && reminders.isEmpty()) return Result.success()
        try {
            RecordingService.monitor(applicationContext)
            repository.log(null, null, "INFO", "BACKGROUND_MONITOR_RESTORE", "系统调度已请求恢复后台持续检测")
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (restricted: IllegalStateException) {
            BackgroundMonitorStatus.blocked(applicationContext, restricted.message ?: "后台启动被系统限制")
            repository.log(null, null, "WARN", "BACKGROUND_START_BLOCKED", "系统限制了后台启动录制服务；当前将继续检查开播状态；${DiagnosticFailure.describe(restricted)}")
        } catch (restricted: SecurityException) {
            BackgroundMonitorStatus.blocked(applicationContext, restricted.message ?: "后台启动被系统拒绝")
            repository.log(null, null, "WARN", "BACKGROUND_START_BLOCKED", "系统拒绝启动后台服务；${DiagnosticFailure.describe(restricted)}")
        }
        val semaphore = Semaphore(4)
        val reminderNotifications = LiveReminderNotifications(applicationContext)
        coroutineScope {
            val roomChecks = rooms.map { room -> async(Dispatchers.IO) {
                if (!repository.monitoringDue(room)) {
                    if (room.oneTimeEndAt != null && System.currentTimeMillis() >= room.oneTimeEndAt) {
                        runCatching { applicationContext.startService(RecordingService.stopIntent(applicationContext, room.id)) }
                            .onFailure { repository.log(room.id, null, "WARN", "ONE_TIME_STOP_DEFERRED", "后台限制导致停止命令延后，将在应用恢复时重试") }
                    }
                    return@async
                }
                if (room.taskStatus == com.streamcap.mobile.domain.model.TaskStatus.PAUSED) return@async
                if (repository.activeSessionForRoom(room.id) != null) return@async
                semaphore.withPermit {
                    val status = try {
                        repository.refresh(room.id)
                            .also { if (it != LiveStatus.ERROR) BackgroundMonitorStatus.checked(applicationContext) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        repository.log(room.id, null, "ERROR", "AUTO_MONITOR_FAILED", DiagnosticFailure.describe(error))
                        return@withPermit
                    }
                    if (status != LiveStatus.LIVE) return@withPermit
                    try {
                        RecordingService.start(applicationContext, room.id)
                    } catch (restricted: IllegalStateException) {
                        BackgroundMonitorStatus.blocked(applicationContext, restricted.message ?: "后台录制启动被限制")
                        repository.log(room.id, null, "WARN", "BACKGROUND_START_BLOCKED", "已检测到开播，但系统限制后台启动；${DiagnosticFailure.describe(restricted)}")
                    } catch (restricted: SecurityException) {
                        BackgroundMonitorStatus.blocked(applicationContext, restricted.message ?: "后台录制启动被拒绝")
                        repository.log(room.id, null, "WARN", "BACKGROUND_START_BLOCKED", "已检测到开播，但系统拒绝启动录制服务；${DiagnosticFailure.describe(restricted)}")
                    } catch (error: Exception) {
                        repository.log(room.id, null, "ERROR", "AUTO_MONITOR_FAILED", DiagnosticFailure.describe(error))
                    }
                }
            } }
            val reminderChecks = reminders.map { reminder -> async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (repository.checkReminder(reminder.id, reminderNotifications.canNotify(reminder.strongNotificationEnabled))) {
                        runCatching { reminderNotifications.notifyOpened(reminder, reminder.strongNotificationEnabled) }
                            .onFailure { error ->
                                repository.logIfDue(
                                    null,
                                    null,
                                    "WARN",
                                    "REMINDER_NOTIFICATION_FAILED_${reminder.id}",
                                    "主播提醒 #${reminder.id} ${reminder.anchorName} 已检测到开播，但通知发送失败；${DiagnosticFailure.describe(error)}",
                                    REMINDER_FAILURE_LOG_INTERVAL_MILLIS,
                                )
                            }
                    }
                }
            } }
            (roomChecks + reminderChecks).awaitAll()
        }
        return Result.success()
    }

    companion object {
        private const val REMINDER_FAILURE_LOG_INTERVAL_MILLIS = 5 * 60_000L
        private const val PERIODIC_NAME = "streamcap-room-monitor"
        private const val IMMEDIATE_NAME = "streamcap-room-monitor-now"
        private const val RECOVERY_POLICY = "background-recovery-policy"

        private fun bootCount(context: Context): Int = android.provider.Settings.Global.getInt(
            context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)

        fun schedule(context: Context, allowMobileData: Boolean, immediate: Boolean = true) {
            context.getSharedPreferences(RECOVERY_POLICY, Context.MODE_PRIVATE).edit()
                .putInt("scheduledBoot", bootCount(context)).apply()
            val manager = WorkManager.getInstance(context)
            val networkType = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<RoomMonitorWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build(),
            )
            if (immediate) manager.enqueueUniqueWork(
                IMMEDIATE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RoomMonitorWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }

        suspend fun schedule(
            context: Context,
            preferences: AppPreferences,
            repository: StreamCapRepository,
        ) {
            val settings = preferences.settings.first()
            val hasReminders = repository.hasEnabledReminders()
            if (BackgroundMonitoringPolicy.enabled(settings) || hasReminders) schedule(context, settings.allowMobileData, immediate = false)
            else cancelSchedule(context)
        }

        fun cancelSchedule(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC_NAME)
                cancelUniqueWork(IMMEDIATE_NAME)
            }
        }
    }
}
