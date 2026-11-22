package com.streamcap.mobile.service

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.diagnostics.DiagnosticFailure
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.notification.LiveReminderNotifications
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.recording.engine.StopReason
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Owns periodic live-room and reminder checks for one foreground service instance. */
@ServiceScoped
class RecordingMonitorCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val networkPolicy: RecordingNetworkPolicy,
    private val repository: StreamCapRepository,
    private val runtimeState: RecordingRuntimeState,
) {
    fun start(
        scope: CoroutineScope,
        notifications: RecordingNotifications,
        isRetryPending: (roomId: Long) -> Boolean,
        startRecording: (roomId: Long, stopAtMillis: Long?) -> Unit,
        stopRecording: (roomId: Long, reason: StopReason) -> Unit,
        acquireWakeLock: () -> Unit,
        releasePowerLocks: () -> Unit,
        stopIfIdle: () -> Unit,
    ) {
        if (runtimeState.monitorJob?.isActive == true) return
        val generation = runtimeState.beginMonitorGeneration()
        var restartScheduled = false
        val monitorJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!runtimeState.activateMonitorGeneration(generation)) return@launch
            try {
                val reminderNotifications = LiveReminderNotifications(context)
                repository.repairPausedRoomSettings()
                runtimeState.powerSavingMonitoring = preferences.settings.first().powerSavingMonitoring
                if (!runtimeState.powerSavingMonitoring) acquireWakeLock()
                repository.log(
                    null,
                    null,
                    "INFO",
                    "BACKGROUND_MONITOR_STARTED",
                    "后台持续检测已启动；省电模式=${runtimeState.powerSavingMonitoring}；熄屏时继续等待开播",
                )
                var networkBlocked: Boolean? = null
                while (runtimeState.isMonitorGenerationCurrent(generation)) {
                    runtimeState.powerSavingMonitoring = preferences.settings.first().powerSavingMonitoring
                    BackgroundMonitorStatus.running(context)
                    if (runtimeState.powerSavingMonitoring && runtimeState.active.isEmpty() && runtimeState.starting.isEmpty()) {
                        releasePowerLocks()
                    } else {
                        acquireWakeLock()
                    }
                    if (!networkPolicy.allowsRequests()) {
                        if (networkBlocked != true) {
                            repository.log(
                                null,
                                null,
                                "WARN",
                                "BACKGROUND_MONITOR_NETWORK_BLOCKED",
                                "当前网络策略禁止请求，监控轮询已暂停；网络条件恢复后会自动继续",
                            )
                        }
                        networkBlocked = true
                        delay(pollIntervalMillis())
                        continue
                    }
                    if (networkBlocked == true) {
                        repository.log(null, null, "INFO", "BACKGROUND_MONITOR_NETWORK_RESTORED", "网络策略已允许请求，恢复直播间检查")
                    }
                    networkBlocked = false
                    repository.resetStaleChecks()
                    val settings = preferences.settings.first()
                    val rooms = if (settings.globalAutoRecordEnabled && settings.backgroundMonitoringEnabled) {
                        repository.monitoredRooms()
                    } else emptyList()
                    val reminders = repository.enabledReminders()
                    if (rooms.isEmpty() && reminders.isEmpty()) break
                    context.getSystemService(NotificationManager::class.java).notify(
                        RecordingNotifications.SERVICE_NOTIFICATION_ID,
                        notifications.serviceNotification(
                            if (runtimeState.active.isEmpty()) {
                                "${rooms.size} 个直播间 · ${reminders.size} 位主播提醒"
                            } else "${runtimeState.active.size} 个任务录制中",
                        ),
                    )
                    rooms.forEach { room ->
                        if (!repository.monitoringDue(room)) {
                            if (room.oneTimeEndAt != null && System.currentTimeMillis() >= room.oneTimeEndAt) {
                                if (runtimeState.active.containsKey(room.id)) stopRecording(room.id, StopReason.TIMER)
                            }
                            return@forEach
                        }
                        if (room.taskStatus == TaskStatus.PAUSED ||
                            runtimeState.stopRequested.contains(room.id) ||
                            runtimeState.pauseRequested.contains(room.id) ||
                            isRetryPending(room.id) ||
                            runtimeState.active.containsKey(room.id) ||
                            runtimeState.starting.contains(room.id)
                        ) return@forEach

                        val checkIntervalMillis = room.checkIntervalSeconds.coerceAtLeast(MIN_MONITOR_INTERVAL_SECONDS) * 1_000L
                        val checkDue = room.lastCheckAt?.let { System.currentTimeMillis() - it >= checkIntervalMillis } ?: true
                        if (!checkDue) return@forEach
                        repository.logIfDue(
                            room.id,
                            null,
                            "INFO",
                            "BACKGROUND_CHECK",
                            "后台监控仍在运行；平台=${room.platform}；检查间隔=${room.checkIntervalSeconds} 秒",
                            BACKGROUND_HEARTBEAT_LOG_INTERVAL_MILLIS,
                        )
                        runCatching { repository.refresh(room.id) }
                            .onSuccess { status ->
                                if (status != LiveStatus.ERROR) BackgroundMonitorStatus.checked(context)
                                if (status == LiveStatus.LIVE) {
                                    repository.log(room.id, null, "INFO", "BACKGROUND_LIVE_DETECTED", "后台已检测到开播，准备启动录制")
                                    startRecording(room.id, null)
                                }
                            }
                            .onFailure { error ->
                                repository.log(room.id, null, "ERROR", "AUTO_MONITOR_FAILED", DiagnosticFailure.describe(error))
                            }
                    }
                    val reminderInterval = settings.checkIntervalSeconds.coerceIn(
                        MIN_MONITOR_INTERVAL_SECONDS,
                        3_600,
                    ) * 1_000L
                    reminders.forEach { reminder ->
                        val checkDue = reminder.lastCheckAt?.let {
                            System.currentTimeMillis() - it >= reminderInterval
                        } ?: true
                        if (!checkDue) return@forEach
                        if (repository.checkReminder(reminder.id, reminderNotifications.canNotify(reminder.strongNotificationEnabled))) {
                            runCatching { reminderNotifications.notifyOpened(reminder, reminder.strongNotificationEnabled) }
                                .onFailure { error ->
                                    repository.logIfDue(
                                        null,
                                        null,
                                        "WARN",
                                        "REMINDER_NOTIFICATION_FAILED_${reminder.id}",
                                        "主播提醒 #${reminder.id} ${reminder.anchorName} 已检测到开播，但通知发送失败；${com.streamcap.mobile.diagnostics.DiagnosticFailure.describe(error)}",
                                        REMINDER_FAILURE_LOG_INTERVAL_MILLIS,
                                    )
                                }
                        }
                    }
                    delay(pollIntervalMillis())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                repository.log(null, null, "ERROR", "MONITOR_LOOP_FAILED", DiagnosticFailure.describe(error))
                restartScheduled = true
                scope.launch {
                    delay(MONITOR_POLL_MILLIS)
                    if (runtimeState.isMonitorGenerationCurrent(generation) &&
                        (repository.monitoredRooms().isNotEmpty() || repository.enabledReminders().isNotEmpty())
                    ) {
                        start(scope, notifications, isRetryPending, startRecording, stopRecording,
                            acquireWakeLock, releasePowerLocks, stopIfIdle)
                    } else if (runtimeState.isMonitorGenerationCurrent(generation)) {
                        if (runtimeState.finishMonitorGeneration(generation)) {
                            BackgroundMonitorStatus.stopped(context)
                            stopIfIdle()
                        }
                    }
                }
            } finally {
                if (!restartScheduled && runtimeState.finishMonitorGeneration(generation)) {
                    BackgroundMonitorStatus.stopped(context)
                    stopIfIdle()
                }
            }
        }
        if (runtimeState.attachMonitorJob(generation, monitorJob)) monitorJob.start()
        else monitorJob.cancel()
    }

    fun stop(stopIfIdle: () -> Unit) {
        runtimeState.invalidateMonitorGeneration()?.cancel()
        BackgroundMonitorStatus.stopped(context)
        stopIfIdle()
    }

    fun dispose() {
        runtimeState.invalidateMonitorGeneration()?.cancel()
        BackgroundMonitorStatus.stopped(context)
    }

    private fun pollIntervalMillis(): Long =
        if (runtimeState.powerSavingMonitoring) POWER_SAVING_POLL_MILLIS else MONITOR_POLL_MILLIS

    private companion object {
        const val MONITOR_POLL_MILLIS = 15_000L
        const val POWER_SAVING_POLL_MILLIS = 60_000L
        const val MIN_MONITOR_INTERVAL_SECONDS = 15
        const val BACKGROUND_HEARTBEAT_LOG_INTERVAL_MILLIS = 30 * 60_000L
        const val REMINDER_FAILURE_LOG_INTERVAL_MILLIS = 5 * 60_000L
    }
}
