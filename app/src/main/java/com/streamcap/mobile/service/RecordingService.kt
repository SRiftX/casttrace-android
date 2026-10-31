package com.streamcap.mobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import com.streamcap.mobile.danmaku.DanmakuSegmentArchive
import com.streamcap.mobile.storage.RecordingStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class RecordingService : Service() {
    // Network checks, database updates, and recorder startup must never run on the UI thread.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retries = RecordingRetryScheduler(serviceScope)
    private val serviceCreatedAt = System.currentTimeMillis()
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var networkPolicy: RecordingNetworkPolicy
    @Inject lateinit var httpClient: OkHttpClient
    @Inject lateinit var recordingStorage: RecordingStorage
    @Inject lateinit var repository: StreamCapRepository
    @Inject lateinit var runtimeState: RecordingRuntimeState
    @Inject lateinit var lineSelector: RecordingLineSelector
    @Inject lateinit var recoveryCoordinator: RecordingRecoveryCoordinator
    @Inject lateinit var publicationCoordinator: RecordingPublicationCoordinator
    @Inject lateinit var powerLockController: RecordingPowerLockController
    @Inject lateinit var monitorCoordinator: RecordingMonitorCoordinator
    @Inject lateinit var progressCoordinator: RecordingProgressCoordinator
    @Inject lateinit var startupCoordinator: RecordingStartupCoordinator
    private val active get() = runtimeState.active
    private val starting get() = runtimeState.starting
    private val stopRequested get() = runtimeState.stopRequested
    private val pauseRequested get() = runtimeState.pauseRequested
    private val resumeRequested get() = runtimeState.resumeRequested
    private val retryCounts get() = runtimeState.retryCounts
    private val sourceAttempts get() = runtimeState.sourceAttempts
    private var powerSavingMonitoring: Boolean
        get() = runtimeState.powerSavingMonitoring
        set(value) { runtimeState.powerSavingMonitoring = value }
    private val monitoringPreparationMutex get() = runtimeState.monitoringPreparationMutex
    private var monitoringActive: Boolean
        get() = runtimeState.monitoringActive
        set(value) { runtimeState.monitoringActive = value }
    private val notificationDismissReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending()) {
                stopIfIdle()
            }
        }
    }
    private lateinit var notifications: RecordingNotifications
    private val startupHost = object : RecordingStartupHost {
        override fun hasRetryPending(roomId: Long): Boolean = retries.isPending(roomId)
        override fun acquireWakeLock() = this@RecordingService.acquireWakeLock()
        override fun enforceNetworkPolicy() = this@RecordingService.enforceNetworkPolicy()
        override fun pauseRecording(roomId: Long, reason: StopReason, disableAutomaticRecording: Boolean) =
            this@RecordingService.pauseRecording(roomId, reason, disableAutomaticRecording)
        override suspend fun scheduleRetry(roomId: Long, stopAtMillis: Long?, retryCount: Int) =
            this@RecordingService.scheduleRetry(roomId, stopAtMillis, retryCount)
        override suspend fun roomTaskStatus(roomId: Long): TaskStatus =
            this@RecordingService.roomTaskStatus(roomId)
        override suspend fun recordingStarted(recording: ActiveRecording) {
            scheduleStop(recording)
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.RECORDING)
            observeRecording(recording)
        }
        override fun stopIfIdle() = this@RecordingService.stopIfIdle()
    }
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { enforceNetworkPolicy() }
        override fun onLost(network: android.net.Network) { enforceNetworkPolicy() }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            enforceNetworkPolicy()
        }
    }

    private fun enforceNetworkPolicy() {
        serviceScope.launch {
            if (networkPolicy.allowsRequests()) return@launch
            (active.keys + starting).distinct().forEach { roomId ->
                if (pauseRequested.contains(roomId)) return@forEach
                pauseRecording(roomId, StopReason.NETWORK_POLICY)
                repository.log(roomId, active[roomId]?.sessionId, "WARN", "NETWORK_POLICY_PAUSED",
                    "仅 Wi-Fi 模式阻止当前网络，正在保存已录内容；恢复网络后可手动继续")
            }
        }
    }


    override fun onCreate() {
        super.onCreate()
        notifications = RecordingNotifications(this)
        startupCoordinator.initialize(serviceScope)
        powerLockController.initialize()
        startForeground(
            RecordingNotifications.SERVICE_NOTIFICATION_ID,
            notifications.serviceNotification("正在准备任务"),
        )
        ContextCompat.registerReceiver(this, notificationDismissReceiver,
            android.content.IntentFilter(RecordingNotifications.ACTION_SERVICE_NOTIFICATION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        serviceScope.launch {
            preferences.settings.collect { enforceNetworkPolicy() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            prepareMonitoring()
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                val stopAt = intent.getLongExtra(EXTRA_STOP_AT, -1).takeIf { it > 0 }
                if (roomId > 0) {
                    retries.cancel(roomId)
                    stopRequested.remove(roomId)
                    pauseRequested.remove(roomId)
                    resumeRequested.add(roomId)
                    sourceAttempts.remove(roomId)
                    startRecording(roomId, stopAt)
                }
            }

            ACTION_MONITOR_ROOM -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                if (roomId > 0) {
                    stopRequested.remove(roomId)
                    pauseRequested.remove(roomId)
                    prepareMonitoring()
                }
            }

            ACTION_PAUSE -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                if (roomId > 0) {
                    android.util.Log.i("StreamCapSave", "pause requested room=$roomId at=${android.os.SystemClock.elapsedRealtime()}")
                    pauseRecording(roomId, disableAutomaticRecording = true)
                }
            }

            ACTION_STOP -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                if (roomId > 0) stopRecording(roomId, StopReason.USER)
            }

            ACTION_DELETE_ROOM -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                if (roomId > 0) cancelRoom(roomId)
            }

            ACTION_RECOVER -> recoverInterruptedTasks(intent.getLongExtra("recoverySessionId", -1).takeIf { it > 0 })

            ACTION_MONITOR -> prepareMonitoring()
            ACTION_STOP_MONITOR -> stopMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending()) {
            val prefs = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
            val generation = prefs.getLong(RECOVERY_GENERATION, 0L) + 1L
            prefs.edit().putLong(RECOVERY_GENERATION, generation).apply()
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = ServiceRestartReceiver.ACTION_RESTART
                putExtra(ServiceRestartReceiver.EXTRA_GENERATION, generation)
            }
            val pending = android.app.PendingIntent.getBroadcast(
                this, RESTART_REQUEST_CODE, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(android.app.AlarmManager::class.java).setAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
                pending,
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(notificationDismissReceiver)
        getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        monitorCoordinator.dispose()
        active.values.forEach { recording ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { recording.danmakuController?.stop("service-destroyed") }
                recording.stopReason = StopReason.SERVICE_DESTROYED
                runCatching { recording.recorder.stop(recording.handle, StopReason.SERVICE_DESTROYED) }
            }
        }
        powerLockController.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        monitorCoordinator.start(
            scope = serviceScope,
            notifications = notifications,
            isRetryPending = retries::isPending,
            startRecording = ::startRecording,
            stopRecording = ::stopRecording,
            acquireWakeLock = ::acquireWakeLock,
            releasePowerLocks = ::releasePowerLocks,
            stopIfIdle = ::stopIfIdle,
        )
    }

    private fun prepareMonitoring() {
        serviceScope.launch {
            monitoringPreparationMutex.withLock {
                val settings = preferences.settings.first()
                val reminders = repository.enabledReminders()
                if (!settings.globalAutoRecordEnabled && reminders.isEmpty()) {
                    RoomMonitorWorker.cancelSchedule(applicationContext)
                    stopMonitoring()
                    return@withLock
                }
                // Recovery from process loss is independent of opting into boot recovery.
                if (BackgroundMonitoringPolicy.enabled(settings) || reminders.isNotEmpty()) {
                    RoomMonitorWorker.schedule(applicationContext, settings.allowMobileData, immediate = false)
                } else {
                    RoomMonitorWorker.cancelSchedule(applicationContext)
                }
                if (settings.globalAutoRecordEnabled) {
                    recoverInterruptedTasksNow()
                    repository.resetStaleChecks()
                    // Only an explicit ACTION_START clears a user's stop/pause request.
                    scanMonitoredRoomsNow()
                }
                if (reminders.isNotEmpty() || settings.backgroundMonitoringEnabled) startMonitoring()
                else stopMonitoring()
            }
        }
    }

    private suspend fun scanMonitoredRoomsNow() {
        if (!networkPolicy.allowsRequests()) return
        repository.monitoredRooms().forEach { room ->
            if (!repository.monitoringDue(room)) return@forEach
            if (room.taskStatus == TaskStatus.PAUSED || retries.isPending(room.id) || active.containsKey(room.id) || starting.contains(room.id)) return@forEach
            runCatching { repository.refresh(room.id) }
                .onSuccess { status ->
                    if (status != LiveStatus.ERROR) BackgroundMonitorStatus.checked(applicationContext)
                    if (status == LiveStatus.LIVE) startRecording(room.id, null)
                }
                .onFailure { error ->
                    repository.log(room.id, null, "ERROR", "STARTUP_SCAN_FAILED", error.message ?: "启动扫描失败")
                }
        }
    }

    private fun startRecording(roomId: Long, stopAtMillis: Long?) {
        startupCoordinator.start(this, notifications, roomId, stopAtMillis, startupHost)
    }

    private suspend fun observeRecording(recording: ActiveRecording) {
        val terminal = progressCoordinator.awaitTerminal(recording, notifications)

        repository.log(recording.roomId, recording.sessionId, "INFO", "RECORDING_STOP_REASON",
            "time=${System.currentTimeMillis()} sessionId=${recording.sessionId} reason=${recording.stopReason ?: when {
                terminal.liveEnded -> "LIVE_ENDED"
                terminal.state == RecorderState.FAILED -> "STREAM_RETRY_EXHAUSTED"
                else -> "STREAM_ENDED"
            }} bytes=${terminal.bytesWritten} part=${terminal.outputFiles.size}")
        val saveStartedAt = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("StreamCapSave", "writer closed room=${recording.roomId} session=${recording.sessionId} at=$saveStartedAt bytes=${terminal.bytesWritten}")

        recording.timerJob?.cancel()
        val danmakuStats = runCatching {
            withContext(Dispatchers.IO) { recording.danmakuController?.stop("media-ended") }
        }.onFailure {
            repository.log(recording.roomId, recording.sessionId, "WARN", "DANMAKU_SAVE_FAILED", "弹幕保存失败，继续保存视频")
        }.getOrNull()
        danmakuStats?.let { stats ->
            repository.session(recording.sessionId)?.let { entity ->
                repository.updateSession(entity.copy(
                    danmakuEventCount = stats.written,
                    danmakuDroppedCount = stats.dropped,
                ))
            }
        }
        repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.FINALIZING)
        var session = repository.session(recording.sessionId) ?: return finishActive(recording.roomId)
        repository.updateSession(
            session.copy(
                status = TaskStatus.FINALIZING,
                errorMessage = "正在保存录制文件并修复索引",
            ),
        )
        if (recording.notificationsEnabled) {
            notifications.notifyRoom(
                recording.roomId,
                notifications.recordingNotification(
                    recording.roomId,
                    recording.roomName,
                            "正在保存录制文件并修复索引",
                    ongoing = true,
                ),
            )
        }
        // Checkpoint publishing updates the database while this observer is running.
        // Reload before finalization so the terminal update cannot overwrite the
        // accumulated outputUris (and their duration) with the session-start snapshot.
        session = repository.session(recording.sessionId) ?: session
        val recordedUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
        if (recordedUris.isNotEmpty()) {
            val recoveredUris = withContext(Dispatchers.IO) {
                runCatching { recordingStorage.publishedSiblingParts(recordedUris, session.roomName, session.startedAt) }
                    .getOrDefault(recordedUris)
            }
            if (recoveredUris.size > recordedUris.size) {
                session = session.copy(outputUri = recoveredUris.first(), outputUris = recoveredUris)
                repository.updateSession(session)
            }
        }
        // An explicit pause wins over a transport error raised while cancelling the HTTP call.
        val userPaused = pauseRequested.contains(recording.roomId)
        val stopRequestedByUser = stopRequested.contains(recording.roomId)
        val intentionalStop = terminal.state == RecorderState.STOPPED || stopRequestedByUser || userPaused
        val persistedOutputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
        val persistedPublishedDuration = publicationCoordinator.publishedDurationSeconds(persistedOutputUris)
        if (userPaused && terminal.bytesWritten == 0L) {
            repository.updateSession(
                session.copy(
                    status = TaskStatus.PAUSED,
                    endedAt = System.currentTimeMillis(),
                    durationSeconds = persistedPublishedDuration.takeIf { it > 0L } ?: session.durationSeconds,
                    errorMessage = null,
                ),
            )
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.PAUSED)
            repository.log(recording.roomId, recording.sessionId, "INFO", "RECORDING_PAUSED", "录制已暂停，当前尚未写入媒体数据")
            pauseRequested.remove(recording.roomId)
            finishActive(recording.roomId)
            return
        }
        val currentRoom = repository.room(recording.roomId)
        val liveStatus = when {
            terminal.liveEnded -> LiveStatus.OFFLINE
            !intentionalStop -> repository.recordingLiveStatus(recording.roomId)
            else -> currentRoom?.liveStatus ?: LiveStatus.UNKNOWN
        }
        val liveEnded = liveStatus == LiveStatus.OFFLINE && !intentionalStop
        val isHuya = recording.platform in setOf("huya", "虎牙直播")
        val hlsFallback = !isHuya && terminal.retryExhausted && recording.streamType == StreamType.FLV && runCatching {
            val candidates = lineSelector.availableSources(recording.roomId).filter { it.type == StreamType.HLS }
            candidates.isNotEmpty() && HlsSourceSelector(httpClient).select(candidates).url.isNotBlank()
        }.getOrDefault(false)
        val terminalError = terminal.errorMessage ?: "没有写入有效媒体数据"
        val retryCount = retryCounts[recording.roomId] ?: 0
        val retryDecision = RecordingRetryPolicy.terminal(
            state = terminal.state,
            bytesWritten = terminal.bytesWritten,
            retryExhausted = terminal.retryExhausted,
            liveEnded = liveEnded,
            stopRequested = stopRequestedByUser,
            pauseRequested = userPaused,
            stopAtMillis = recording.stopAtMillis,
            nowMillis = System.currentTimeMillis(),
            roomEnabled = currentRoom?.enabled == true,
            scheduledMonitoringEnabled = currentRoom?.scheduledMonitoringEnabled == true,
            retryCount = retryCount,
            hlsFallback = hlsFallback,
        )
        val disconnectPaused = retryDecision.disconnectPaused
        val paused = retryDecision.paused
        val shouldRetry = retryDecision.shouldRetry
        // 断流前已写入的媒体先发布，再刷新线路续录。
        val publishBeforeRetry = retryDecision.publishBeforeRetry
        if (liveEnded) {
            repository.log(recording.roomId, recording.sessionId, "INFO", "LIVE_ENDED", "主播已下播，保存已有内容并结束本次录制")
            if (terminal.bytesWritten == 0L && persistedOutputUris.isEmpty()) {
                repository.deleteSession(session)
                terminal.outputFiles.forEach(File::delete)
                repository.setRoomTaskStatus(recording.roomId, LiveStatus.OFFLINE, roomTaskStatus(recording.roomId))
                retryCounts.remove(recording.roomId)
                sourceAttempts.remove(recording.roomId)
                finishActive(recording.roomId)
                return
            }
        }
        if (disconnectPaused && terminal.bytesWritten == 0L && persistedOutputUris.isEmpty()) {
            repository.updateSession(session.copy(status = TaskStatus.PAUSED,
                endedAt = System.currentTimeMillis(), errorMessage = "断流已暂停，当前没有可保存的媒体"))
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.PAUSED)
            finishActive(recording.roomId)
            return
        }
        if (!liveEnded && !disconnectPaused && ((terminal.state == RecorderState.FAILED && !intentionalStop) || terminal.bytesWritten == 0L)) {
            if (shouldRetry && terminal.bytesWritten == 0L) {
                retryCounts[recording.roomId] = retryCount + 1
                sourceAttempts[recording.roomId] = if (hlsFallback) 3 else (sourceAttempts[recording.roomId] ?: 0) + 1
                repository.updateSession(
                    session.copy(
                        status = TaskStatus.FAILED,
                        endedAt = System.currentTimeMillis(),
                        errorMessage = "$terminalError；正在刷新直播地址并重试",
                    ),
                )
                repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.RECONNECTING)
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "INFO",
                    "RECORDING_RETRY",
                    "$terminalError；正在刷新并进行第 ${retryCount + 1} 次重试",
                )
                // 0B 失败属于线路探测，错误保留在日志中，不在录制列表制造重复记录。
                repository.deleteSession(session)
                terminal.outputFiles.forEach { it.delete() }
                recording.temporaryFile.delete()
                scheduleRetry(recording.roomId, recording.stopAtMillis, retryCount)
                active.remove(recording.roomId)
                return
            }
            if (terminal.bytesWritten > 0L) {
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "INFO",
                    "RECORDING_PARTIAL_SAVED",
                    if (publishBeforeRetry) "$terminalError；先保存已录内容，再刷新地址续录" else "$terminalError；保留并保存已录内容",
                )
            } else {
            repository.updateSession(
                session.copy(
                    status = TaskStatus.FAILED,
                    endedAt = System.currentTimeMillis(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = terminal.durationSeconds,
                    errorMessage = terminalError,
                ),
            )
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.FAILED)
            repository.log(
                recording.roomId,
                recording.sessionId,
                "ERROR",
                "RECORDING_FAILED",
                terminalError,
            )
            finishActive(recording.roomId)
            retryCounts.remove(recording.roomId)
            sourceAttempts.remove(recording.roomId)
            return
            }
        }

        val publishResult = runCatching {
            val publication = publicationCoordinator.publishTerminal(
                recording = recording,
                terminal = terminal,
                session = session,
                paused = paused,
                disconnectPaused = disconnectPaused,
                saveStartedAt = saveStartedAt,
            )
            repository.setRoomTaskStatus(
                recording.roomId,
                if (liveEnded) LiveStatus.OFFLINE else liveStatus,
                if (paused) TaskStatus.PAUSED else roomTaskStatus(recording.roomId),
            )
            repository.log(
                recording.roomId,
                recording.sessionId,
                "INFO",
                if (paused) "RECORDING_PAUSED" else "RECORDING_COMPLETED",
                when {
                    disconnectPaused -> "断流已暂停，已保存当前内容；点击继续后可恢复录制"
                    paused -> "录制已暂停，当前内容已保存；点击继续后会建立新的录制分段"
                    liveEnded -> "主播已下播，录制文件已保存"
                    publishBeforeRetry -> "直播地址过期，已保存当前文件并准备自动续录"
                    publication.outputUris.size == 1 -> "录制文件已保存"
                    else -> "录制完成，已保存 ${publication.outputUris.size} 个分段文件"
                },
            )
            if (recording.notificationsEnabled) {
                notifications.notifyRoom(
                    recording.roomId,
                        notifications.recordingNotification(
                            recording.roomId,
                            recording.roomName,
                            if (disconnectPaused) {
                                "断流已暂停，已保存当前内容"
                            } else if (paused) {
                                "录制已暂停，当前内容已保存"
                            } else if (liveEnded) {
                                "主播已下播，录制文件已保存"
                            } else if (publishBeforeRetry) {
                                "当前分段已保存，正在刷新地址续录"
                            } else {
                                "录制完成 · ${progressCoordinator.formatBytes(terminal.bytesWritten)}"
                            },
                            ongoing = false,
                            recoverySessionId = recording.sessionId,
                    ),
                )
            }
        }.onFailure { error ->
            val latestSession = repository.session(recording.sessionId) ?: session
            repository.updateSession(
                latestSession.copy(
                    status = TaskStatus.FAILED,
                    endedAt = System.currentTimeMillis(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = terminal.durationSeconds,
                    errorMessage = "文件发布失败：${error.message}",
                ),
            )
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, roomTaskStatus(recording.roomId))
            if (recording.notificationsEnabled) {
                notifications.notifyRoom(
                    recording.roomId,
                    notifications.recordingNotification(
                        recording.roomId,
                        recording.roomName,
                        "文件发布失败：${error.message ?: "未知错误"}",
                        ongoing = false,
                        recoverySessionId = recording.sessionId,
                    ),
                )
            }
        }
        if (publishResult.isSuccess) {
            val saved = repository.session(recording.sessionId)
            val danmakuPath = saved?.danmakuPath
            val mediaUris = saved?.outputUris.orEmpty().ifEmpty { listOfNotNull(saved?.outputUri) }
            if (danmakuPath != null && mediaUris.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val durations = mediaUris.map { raw ->
                            contentResolver.query(Uri.parse(raw), arrayOf(MediaStore.Video.VideoColumns.DURATION), null, null, null)
                                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
                        }
                        DanmakuSegmentArchive.seal(File(danmakuPath), recording.sessionId, mediaUris, durations)
                    }.onFailure { error ->
                        repository.log(recording.roomId, recording.sessionId, "WARN", "DANMAKU_SEGMENT_DEFERRED",
                            "弹幕分段封存失败，可从原始文件重建：${error.message}")
                    }
                }
            }
        }
        if (publishResult.isSuccess && publishBeforeRetry) {
            retryCounts[recording.roomId] = 0
            sourceAttempts[recording.roomId] = if (hlsFallback) 3 else (sourceAttempts[recording.roomId] ?: 0) + 1
            if (hlsFallback) repository.log(recording.roomId, recording.sessionId, "INFO", "STREAM_REFRESH_RESULT",
                "FLV 线路连续失败；当前分段已保存，下一次尝试 HLS")
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.RECONNECTING)
            scheduleRetry(recording.roomId, recording.stopAtMillis, retryCounts[recording.roomId] ?: 0)
            active.remove(recording.roomId)
            return
        }
        if (paused) pauseRequested.remove(recording.roomId)
        retryCounts.remove(recording.roomId)
        sourceAttempts.remove(recording.roomId)
        finishActive(recording.roomId)
    }

    /**
     * Publish closed recorder segments while the session is still running. The active
     * segment remains private until it is closed at the next boundary, so a process kill
     * can recover it without exposing a half-written media file.
     */
    private fun stopMonitoring() {
        monitorCoordinator.stop(::stopIfIdle)
    }

    private fun scheduleStop(recording: ActiveRecording) {
        val stopAt = recording.stopAtMillis ?: return
        recording.timerJob = serviceScope.launch {
            delay((stopAt - System.currentTimeMillis()).coerceAtLeast(0))
            recording.stopReason = StopReason.TIMER
            recording.recorder.stop(recording.handle, StopReason.TIMER)
            repository.log(recording.roomId, recording.sessionId, "INFO", "TIMER_STOP", "定时停止已触发")
        }
    }

    private suspend fun scheduleRetry(roomId: Long, stopAtMillis: Long?, retryCount: Int) {
        val room = repository.room(roomId)
        if (stopRequested.contains(roomId) || pauseRequested.contains(roomId) || room?.enabled != true ||
            (stopAtMillis != null && stopAtMillis <= System.currentTimeMillis())) {
            if (room != null) repository.setRoomTaskStatus(roomId, room.liveStatus,
                if (pauseRequested.contains(roomId)) TaskStatus.PAUSED else roomTaskStatus(roomId))
            finishActive(roomId)
            return
        }
        repository.log(roomId, null, "INFO", "RECONNECT_WAIT",
            "第 ${retryCount + 1} 次重连，等待 ${RecordingRetryScheduler.delayMillis(retryCount) / 1000} 秒后重新解析直播源")
        retries.schedule(roomId, retryCount) {
            val latest = repository.room(roomId)
            if (stopRequested.contains(roomId) || pauseRequested.contains(roomId) || latest?.enabled != true ||
                (stopAtMillis != null && stopAtMillis <= System.currentTimeMillis())) {
                if (latest != null) repository.setRoomTaskStatus(roomId, latest.liveStatus,
                    if (pauseRequested.contains(roomId)) TaskStatus.PAUSED else roomTaskStatus(roomId))
                finishActive(roomId)
            } else {
                startRecording(roomId, stopAtMillis)
            }
        }
    }

    private fun stopRecording(roomId: Long, reason: StopReason) {
        retries.cancel(roomId)
        stopRequested.add(roomId)
        pauseRequested.remove(roomId)
        retryCounts.remove(roomId)
        sourceAttempts.remove(roomId)
        val recording = active[roomId]
        if (recording != null) {
            recording.stopReason = reason
            serviceScope.launch { recording.recorder.stop(recording.handle, reason) }
        } else {
            serviceScope.launch {
                repository.room(roomId)?.let { repository.setRoomTaskStatus(roomId, it.liveStatus, roomTaskStatus(roomId)) }
                stopIfIdle()
            }
        }
    }

    private fun pauseRecording(
        roomId: Long,
        reason: StopReason = StopReason.PAUSE,
        disableAutomaticRecording: Boolean = false,
    ) {
        retries.cancel(roomId)
        pauseRequested.add(roomId)
        resumeRequested.remove(roomId)
        retryCounts.remove(roomId)
        sourceAttempts.remove(roomId)
        val recording = active[roomId]
        if (recording != null) {
            recording.stopReason = reason
            recording.timerJob?.cancel()
            serviceScope.launch {
                try {
                    if (disableAutomaticRecording) repository.setScheduledMonitoringEnabled(roomId, false)
                } finally {
                    recording.recorder.stop(recording.handle, reason)
                }
            }
        } else {
            serviceScope.launch {
                if (disableAutomaticRecording) repository.setScheduledMonitoringEnabled(roomId, false)
                val room = repository.room(roomId) ?: return@launch
                repository.setRoomTaskStatus(roomId, room.liveStatus, TaskStatus.PAUSED)
                stopIfIdle()
            }
        }
    }

    private fun recoverInterruptedTasks(sessionId: Long? = null) {
        serviceScope.launch { recoverInterruptedTasksNow(sessionId) }
    }

    private fun cancelRoom(roomId: Long) {
        stopRequested.add(roomId)
        pauseRequested.remove(roomId)
        retries.cancel(roomId)
        active[roomId]?.let { recording ->
            recording.stopReason = StopReason.USER
            serviceScope.launch { recording.recorder.stop(recording.handle, StopReason.USER) }
        }
        starting.remove(roomId)
    }

    private suspend fun recoverInterruptedTasksNow(sessionId: Long? = null) {
        recoveryCoordinator.recover(sessionId, serviceCreatedAt) { roomId, stopAt ->
            startRecording(roomId, stopAt)
        }
    }

    private fun finishActive(roomId: Long) {
        active.remove(roomId)
        if (!monitoringActive && active.isEmpty() && starting.isEmpty() && !retries.hasPending()) {
            releasePowerLocks()
        }
        stopIfIdle()
    }

    private suspend fun roomTaskStatus(roomId: Long): TaskStatus =
        if (repository.room(roomId)?.enabled == true) TaskStatus.WAITING else TaskStatus.DISABLED

    private fun stopIfIdle() {
        if (monitoringActive) {
            if (powerSavingMonitoring && active.isEmpty() && starting.isEmpty()) releasePowerLocks()
            val message = if (active.isEmpty()) "正在检测直播状态" else "${active.size} 个录制任务正在运行"
            getSystemService(android.app.NotificationManager::class.java).notify(
                RecordingNotifications.SERVICE_NOTIFICATION_ID,
                notifications.serviceNotification(message),
            )
            return
        }
        if (active.isEmpty() && starting.isEmpty() && !retries.hasPending()) {
            releasePowerLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            val message = "${active.size} 个录制任务正在运行"
            getSystemService(android.app.NotificationManager::class.java).notify(
                RecordingNotifications.SERVICE_NOTIFICATION_ID,
                notifications.serviceNotification(message),
            )
        }
    }

    private fun releasePowerLocks() = powerLockController.release()

    private fun acquireWakeLock() = powerLockController.acquire(
        scope = serviceScope,
        shouldKeepAlive = { monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending() },
        shouldHoldDuringMonitoring = { !powerSavingMonitoring || active.isNotEmpty() || starting.isNotEmpty() },
    )

    companion object {
        private const val ACTION_START = "com.streamcap.mobile.action.START_RECORDING"
        private const val ACTION_STOP = "com.streamcap.mobile.action.STOP_RECORDING"
        private const val ACTION_DELETE_ROOM = "com.streamcap.mobile.action.DELETE_ROOM"
        private const val ACTION_PAUSE = "com.streamcap.mobile.action.PAUSE_RECORDING"
        private const val ACTION_RECOVER = "com.streamcap.mobile.action.RECOVER"
        private const val ACTION_MONITOR = "com.streamcap.mobile.action.MONITOR"
        private const val ACTION_MONITOR_ROOM = "com.streamcap.mobile.action.MONITOR_ROOM"
        private const val ACTION_STOP_MONITOR = "com.streamcap.mobile.action.STOP_MONITOR"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_STOP_AT = "stop_at"
        private const val RECOVERY_PREFS = "service_recovery"
        private const val RECOVERY_GENERATION = "generation"
        private const val RESTART_REQUEST_CODE = 4107
        private const val RESTART_DELAY_MILLIS = 5_000L
        fun start(context: Context, roomId: Long, stopAtMillis: Long? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_ROOM_ID, roomId)
                    stopAtMillis?.let { putExtra(EXTRA_STOP_AT, it) }
                },
            )
        }

        fun stopIntent(context: Context, roomId: Long): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_ROOM_ID, roomId)
            }

        fun deleteRoom(context: Context, roomId: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_DELETE_ROOM
                    putExtra(EXTRA_ROOM_ID, roomId)
                },
            )
        }

        fun pauseIntent(context: Context, roomId: Long): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_ROOM_ID, roomId)
            }

        fun recover(context: Context, sessionId: Long? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_RECOVER)
                    .putExtra("recoverySessionId", sessionId ?: -1L),
            )
        }

        fun monitor(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_MONITOR),
            )
        }

        fun monitorRoomNow(context: Context, roomId: Long) {
            ContextCompat.startForegroundService(context,
                Intent(context, RecordingService::class.java).setAction(ACTION_MONITOR_ROOM).putExtra(EXTRA_ROOM_ID, roomId))
        }

        fun stopMonitoring(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP_MONITOR),
            )
        }
    }
}
