package com.streamcap.mobile.service

import com.streamcap.mobile.domain.model.StreamSource

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.RecordingSegmentPolicy
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.recording.flv.DirectHttpRecorder
import com.streamcap.mobile.recording.flv.FlvFileInspector
import com.streamcap.mobile.recording.hls.HlsRecorder
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import com.streamcap.mobile.recording.ffmpeg.FfmpegHlsRecorder
import com.streamcap.mobile.recording.mp4.RecordingMp4Remuxer
import com.streamcap.mobile.recording.mp4.Mp4Muxer
import com.streamcap.mobile.recording.repair.RecordingFileRepair
import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuController
import com.streamcap.mobile.danmaku.DanmakuEventStore
import com.streamcap.mobile.danmaku.DanmakuSegmentArchive
import com.streamcap.mobile.storage.RecordingNameContext
import com.streamcap.mobile.storage.RecordingNameFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

class RecordingService : Service() {
    private data class ActiveRecording(
        val roomId: Long,
        val roomName: String,
        val platform: String,
        val roomTitle: String?,
        val sessionId: Long,
        val handle: RecordingHandle,
        val recorder: Recorder,
        val temporaryFile: File,
        val streamType: StreamType,
        val outputFormat: String,
        val storageDirectoryUri: String?,
        val notificationsEnabled: Boolean,
        val stopAtMillis: Long?,
        val fileNameTemplate: String,
        val includeTitleInFileName: Boolean,
        val removeEmojiFromFileName: Boolean,
        val danmakuController: DanmakuController? = null,
        val checkpointedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        @Volatile var stopReason: StopReason? = null,
        var timerJob: Job? = null,
    )

    // Network checks, database updates, and recorder startup must never run on the UI thread.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retries = RecordingRetryScheduler(serviceScope)
    private val serviceCreatedAt = System.currentTimeMillis()
    private val recoveryMutex = Mutex()
    private val active = ConcurrentHashMap<Long, ActiveRecording>()
    private val starting = ConcurrentHashMap.newKeySet<Long>()
    private val stopRequested = ConcurrentHashMap.newKeySet<Long>()
    private val pauseRequested = ConcurrentHashMap.newKeySet<Long>()
    private val resumeRequested = ConcurrentHashMap.newKeySet<Long>()
    private val retryCounts = ConcurrentHashMap<Long, Int>()
    private val sourceAttempts = ConcurrentHashMap<Long, Int>()
    private var monitorJob: Job? = null
    @Volatile private var powerSavingMonitoring = false
    private val monitoringPreparationMutex = Mutex()
    @Volatile private var monitoringActive = false
    private val notificationDismissReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending()) {
                stopIfIdle()
            }
        }
    }
    private lateinit var notifications: RecordingNotifications
    private lateinit var directRecorder: DirectHttpRecorder
    private lateinit var hlsRecorder: HlsRecorder
    private lateinit var ffmpegHlsRecorder: FfmpegHlsRecorder
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wifiLock: WifiManager.WifiLock? = null
    private val powerLockGuard = Any()
    private var powerLocksClosed = false
    private var wakeLockRefreshJob: Job? = null
    private val networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { enforceNetworkPolicy() }
        override fun onLost(network: android.net.Network) { enforceNetworkPolicy() }
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            enforceNetworkPolicy()
        }
    }

    private fun enforceNetworkPolicy() {
        serviceScope.launch {
            if (container.networkPolicy.allowsRequests()) return@launch
            (active.keys + starting).distinct().forEach { roomId ->
                if (pauseRequested.contains(roomId)) return@forEach
                pauseRecording(roomId, StopReason.NETWORK_POLICY)
                repository.log(roomId, active[roomId]?.sessionId, "WARN", "NETWORK_POLICY_PAUSED",
                    "仅 Wi-Fi 模式阻止当前网络，正在保存已录内容；恢复网络后可手动继续")
            }
        }
    }

    private val container get() = (application as StreamCapApplication).container
    private val repository get() = container.repository

    override fun onCreate() {
        super.onCreate()
        notifications = RecordingNotifications(this)
        directRecorder = DirectHttpRecorder(container.httpClient, serviceScope)
        hlsRecorder = HlsRecorder(container.httpClient, serviceScope)
        ffmpegHlsRecorder = FfmpegHlsRecorder(serviceScope, container.httpClient)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamCap:Recording",
        ).apply { setReferenceCounted(false) }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StreamCap:Transfer")
            ?.apply { setReferenceCounted(false) }
        startForeground(
            RecordingNotifications.SERVICE_NOTIFICATION_ID,
            notifications.serviceNotification("正在准备任务"),
        )
        ContextCompat.registerReceiver(this, notificationDismissReceiver,
            android.content.IntentFilter(RecordingNotifications.ACTION_SERVICE_NOTIFICATION_DISMISSED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        serviceScope.launch {
            container.preferences.settings.collect { enforceNetworkPolicy() }
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

            ACTION_PAUSE -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                if (roomId > 0) pauseRecording(roomId)
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
        monitorJob?.cancel()
        active.values.forEach { recording ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { recording.danmakuController?.stop("service-destroyed") }
                recording.stopReason = StopReason.SERVICE_DESTROYED
                runCatching { recording.recorder.stop(recording.handle, StopReason.SERVICE_DESTROYED) }
            }
        }
        synchronized(powerLockGuard) {
            powerLocksClosed = true
            releasePowerLocks()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            monitoringActive = true
            try {
                powerSavingMonitoring = container.preferences.settings.first().powerSavingMonitoring
                if (!powerSavingMonitoring) acquireWakeLock()
                repository.log(null, null, "INFO", "BACKGROUND_MONITOR_STARTED", "后台持续检测已启动，熄屏时继续等待开播")
                while (true) {
                    powerSavingMonitoring = container.preferences.settings.first().powerSavingMonitoring
                    BackgroundMonitorStatus.running(applicationContext)
                    if (powerSavingMonitoring && active.isEmpty() && starting.isEmpty()) releasePowerLocks()
                    else acquireWakeLock()
                    if (!container.networkPolicy.allowsRequests()) {
                        delay(if (powerSavingMonitoring) POWER_SAVING_POLL_MILLIS else MONITOR_POLL_MILLIS)
                        continue
                    }
                    repository.resetStaleChecks()
                    val rooms = repository.monitoredRooms()
                    if (rooms.isEmpty()) break
                    getSystemService(android.app.NotificationManager::class.java).notify(
                        RecordingNotifications.SERVICE_NOTIFICATION_ID,
                        notifications.serviceNotification(
                            if (active.isEmpty()) "正在监控 ${rooms.size} 个直播间" else "${active.size} 个任务录制中",
                        ),
                    )
                    rooms.forEach { room ->
                        if (!repository.monitoringDue(room)) {
                            if (room.oneTimeEndAt != null && System.currentTimeMillis() >= room.oneTimeEndAt) {
                                active[room.id]?.let { stopRecording(room.id, StopReason.TIMER) }
                            }
                            return@forEach
                        }
                        if (room.taskStatus == TaskStatus.PAUSED || stopRequested.contains(room.id) || pauseRequested.contains(room.id) || retries.isPending(room.id) || active.containsKey(room.id) || starting.contains(room.id)) {
                            return@forEach
                        }
                        val checkIntervalMillis = room.checkIntervalSeconds.coerceAtLeast(MIN_MONITOR_INTERVAL_SECONDS) * 1_000L
                        val checkDue = room.lastCheckAt?.let { System.currentTimeMillis() - it >= checkIntervalMillis } ?: true
                        if (!checkDue) return@forEach
                        repository.log(room.id, null, "INFO", "BACKGROUND_CHECK", "后台检查开播状态，房间自动录制已启用，检查间隔 ${room.checkIntervalSeconds} 秒")
                        runCatching { repository.refresh(room.id) }
                            .onSuccess { status ->
                                if (status != LiveStatus.ERROR) BackgroundMonitorStatus.checked(applicationContext)
                                if (status == LiveStatus.LIVE) {
                                    repository.log(room.id, null, "INFO", "BACKGROUND_LIVE_DETECTED", "后台已检测到开播，准备启动录制")
                                    startRecording(room.id, null)
                                }
                            }
                            .onFailure { error ->
                                repository.log(
                                    room.id,
                                    null,
                                    "ERROR",
                                    "AUTO_MONITOR_FAILED",
                                    error.message ?: "自动检查开播失败",
                                )
                            }
                    }
                    delay(if (powerSavingMonitoring) POWER_SAVING_POLL_MILLIS else MONITOR_POLL_MILLIS)
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                repository.log(null, null, "ERROR", "MONITOR_LOOP_FAILED", error.message ?: "监控循环异常")
                // 监控异常不能让前台服务永久退出；下一轮仍会重新读取直播间并检测。
                serviceScope.launch {
                    delay(MONITOR_POLL_MILLIS)
                    if (repository.monitoredRooms().isNotEmpty()) startMonitoring()
                }
            } finally {
                monitoringActive = false
                BackgroundMonitorStatus.stopped(applicationContext)
                monitorJob = null
                stopIfIdle()
            }
        }
    }

    private fun prepareMonitoring() {
        serviceScope.launch {
            monitoringPreparationMutex.withLock {
                val settings = container.preferences.settings.first()
                if (!settings.globalAutoRecordEnabled) {
                    stopMonitoring()
                    return@withLock
                }
                // Recovery from process loss is independent of opting into boot recovery.
                if (BackgroundMonitoringPolicy.enabled(settings)) {
                    RoomMonitorWorker.schedule(applicationContext, settings.allowMobileData, immediate = false)
                } else {
                    RoomMonitorWorker.cancelSchedule(applicationContext)
                }
                recoverInterruptedTasksNow()
                repository.resetStaleChecks()
                // Only an explicit ACTION_START clears a user's stop/pause request.
                scanMonitoredRoomsNow()
                if (settings.backgroundMonitoringEnabled) startMonitoring() else stopMonitoring()
            }
        }
    }

    private suspend fun scanMonitoredRoomsNow() {
        if (!container.networkPolicy.allowsRequests()) return
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
        if (stopRequested.contains(roomId) || pauseRequested.contains(roomId)) return
        if (retries.isPending(roomId)) return
        if (stopAtMillis != null && stopAtMillis <= System.currentTimeMillis()) return
        if (active.containsKey(roomId) || !starting.add(roomId)) return
        serviceScope.launch {
            val room = repository.room(roomId) ?: run {
                starting.remove(roomId)
                return@launch
            }
            if (!room.enabled || (room.taskStatus == TaskStatus.PAUSED && !resumeRequested.remove(roomId)) || pauseRequested.contains(roomId)) {
                starting.remove(roomId)
                return@launch
            }
            if (stopAtMillis == null && room.oneTimeEndAt != null && !repository.monitoringDue(room)) {
                starting.remove(roomId)
                return@launch
            }
            if (repository.activeSessionForRoom(roomId) != null) {
                starting.remove(roomId)
                return@launch
            }
            var sessionCreated = false
            var createdSessionId: Long? = null
            var pendingDanmaku: DanmakuController? = null
            try {
                acquireWakeLock()
                container.networkPolicy.requireAllowed()
                repository.setRoomTaskStatus(roomId, room.liveStatus, TaskStatus.STARTING)
                val settings = container.preferences.settings.first()
                val storageRoot = container.recordingStorage.recordingRoot()
                val freeBytes = storageRoot.usableSpace
                check(freeBytes >= MIN_FREE_BYTES) {
                    "可用存储空间不足（至少需要 ${MIN_FREE_BYTES / 1_073_741_824} GB），已取消录制"
                }
                val availableSources = recordingSources(roomId)
                val requestedFormat = room.outputFormat.uppercase()
                // Huya HLS endpoints can reject repeated playlist requests. FLV now
                // renews expired URLs within the same session and preserves its
                // timestamp state and segmentation across reconnects.
                val huyaMp4 = requestedFormat == "MP4" && room.platform in setOf("huya", "虎牙直播")
                // Douyin commonly exposes both HLS and FLV. Its HLS playlist can
                // keep downloading while the segment timestamps fall far behind
                // wall time, producing short media after a long recording. FLV
                // carries a continuous timestamp timeline and is preferable for
                // MP4/default recording; HLS remains a fallback when FLV is absent.
                val preferDouyinFlv = room.platform in setOf("douyin", "抖音直播") &&
                    requestedFormat in setOf("MP4", "FLV") && availableSources.any { it.type == StreamType.FLV }
                val preferHuyaFlv = room.platform in setOf("huya", "虎牙直播") &&
                    (huyaMp4 || room.flvDirectDownload) && availableSources.any { it.type == StreamType.FLV }
                // Huya's mobile HLS signatures are frequently rejected by its CDN
                // (HTTP 403) even while the FLV stream is valid. Falling back to HLS
                // after a short FLV disconnect turns a recoverable reconnect into a
                // guaranteed failure, so keep Huya on the probed FLV path.
                val preferHlsAfterFlvFailures = room.platform !in setOf("huya", "虎牙直播") &&
                    (sourceAttempts[roomId] ?: 0) >= 3 &&
                    availableSources.any { it.type == StreamType.HLS }
                val preferredCandidates = if (preferHlsAfterFlvFailures) {
                    availableSources.filter { it.type == StreamType.HLS }
                } else when (requestedFormat) {
                    "FLV" -> availableSources.filter { it.type == StreamType.FLV }
                    "MP4" -> availableSources.filter {
                        it.type == if (preferHuyaFlv || preferDouyinFlv) StreamType.FLV else StreamType.HLS
                    }
                    "TS" -> availableSources.filter { it.type == StreamType.HLS }
                    else -> availableSources.filter { it.type == if (room.flvDirectDownload) StreamType.FLV else StreamType.HLS }
                }
                // MP4 is an output container; Huya uses the desktop resolver's FLV preference.
                val candidates = preferredCandidates
                    .ifEmpty {
                        val fallbackTypes = if (room.platform in setOf("huya", "虎牙直播")) {
                            setOf(StreamType.FLV)
                        } else {
                            setOf(StreamType.HLS, StreamType.FLV)
                        }
                        availableSources.filter { it.type in fallbackTypes }
                    }
                    .distinctBy { it.url }
                val initialSource = candidates.getOrNull(
                    (sourceAttempts[roomId] ?: 0).mod(candidates.size.coerceAtLeast(1)),
                ) ?: error(if (availableSources.isEmpty()) "主播当前未开播，或平台没有返回可用直播源" else "当前线路不提供 $requestedFormat 格式")
                val source = if (initialSource.type == StreamType.HLS) {
                    try {
                        HlsSourceSelector(container.httpClient).select(candidates.filter { it.type == StreamType.HLS }, room.quality)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (requestedFormat != "MP4") throw failure
                        availableSources.firstOrNull { it.type == StreamType.FLV } ?: throw failure
                    }
                } else initialSource
                if (room.quality == "原画" && source.quality != "原画") {
                    repository.log(
                        roomId,
                        null,
                        "WARN",
                        "QUALITY_FALLBACK",
                        "请求原画但平台返回 ${source.quality} 线路${source.bitrate?.let { "（${it / 1000} kbps）" } ?: ""}，将按实际线路无损保存",
                    )
                } else {
                    repository.log(
                        roomId,
                        null,
                        "INFO",
                        "QUALITY_SELECTED",
                        "已选择 ${source.quality} 线路${source.bitrate?.let { "（${it / 1000} kbps）" } ?: ""}，录制阶段不会转码",
                    )
                }
                val actualFormat = when {
                    requestedFormat == "MP4" -> "MP4"
                    source.type == StreamType.FLV -> "FLV"
                    source.type == StreamType.HLS -> "TS"
                    else -> error("暂不支持录制 ${source.type} 类型的直播源")
                }
                if (actualFormat != requestedFormat) {
                    repository.log(
                        roomId,
                        null,
                        "WARN",
                        "RECORDING_FORMAT_FALLBACK",
                        "平台当前没有 $requestedFormat 兼容线路，本次使用真实的 $actualFormat 直播源录制",
                    )
                }
                val recorderExtension = if (source.type == StreamType.FLV) "flv" else "ts"
                val temporary = container.recordingStorage.createTemporaryFile(
                    room.anchorName,
                    recorderExtension,
                )
                if (stopRequested.contains(roomId) || pauseRequested.contains(roomId) || repository.room(roomId)?.enabled != true) {
                    temporary.delete()
                    starting.remove(roomId)
                    return@launch
                }
                val effectiveStopAt = listOfNotNull(stopAtMillis, room.oneTimeEndAt).minOrNull()
                val sessionId = repository.createSession(
                    room = room,
                    source = source,
                    temporaryPath = temporary.path,
                    stopAtMillis = effectiveStopAt,
                    outputFormat = actualFormat,
                )
                sessionCreated = true
                createdSessionId = sessionId
                val danmakuController = if (container.danmakuClients.supports(room.platform)) {
                    val eventFile = File(filesDir, "danmaku/session-$sessionId/events.jsonl")
                    val store = DanmakuEventStore(eventFile)
                    repository.session(sessionId)?.let { entity ->
                        repository.updateSession(entity.copy(danmakuPath = eventFile.absolutePath))
                    }
                    DanmakuController(
                        scope = serviceScope,
                        client = container.danmakuClients.forPlatform(room.platform),
                        store = store,
                    ) { state ->
                        serviceScope.launch {
                            repository.log(roomId, sessionId, if (state.startsWith("error")) "WARN" else "INFO",
                                "DANMAKU_$state", "弹幕连接状态：$state")
                        }
                    }
                } else null
                pendingDanmaku = danmakuController
                repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.STARTING)
                repository.log(roomId, sessionId, "INFO", "RECORDING_START", "开始录制 ${source.type}")

                // Persist HLS segments before remuxing the finished files to MP4.
                val selectedRecorder: Recorder = if (source.type == StreamType.HLS) {
                    if (source.separateAudio) ffmpegHlsRecorder else hlsRecorder
                } else {
                    directRecorder
                }
                var lastFlvSource = source
                var lastHlsHost = java.net.URI(source.url).host
                container.networkPolicy.requireAllowed()
                check(!pauseRequested.contains(roomId) && !stopRequested.contains(roomId)) { "录制启动已取消" }
                val mediaStart = android.os.SystemClock.elapsedRealtime()
                val handle = selectedRecorder.start(
                    RecordingRequest(
                        url = source.url,
                        headers = source.headers,
                        streamType = source.type,
                        destination = temporary,
                        // 旧数据库可能仍保存 1 分钟等历史值，录制入口再次兜底到 30 分钟。
                        segmentSeconds = RecordingSegmentPolicy.effectiveSeconds(room.segmentEnabled, room.segmentSeconds),
                        eventLogger = { type, fields ->
                            serviceScope.launch {
                                val details = (mapOf("time" to System.currentTimeMillis().toString(),
                                    "sessionId" to sessionId.toString()) + fields)
                                    .entries.joinToString(" ") { (key, value) -> "$key=$value" }
                                android.util.Log.i("StreamCapEvent", "$type $details")
                                repository.log(roomId, sessionId, "INFO", type, details)
                            }
                        },
                        // HLS 签名过期与 CDN 断流和是否开启自动监控无关，手动录制也必须能刷新线路。
                        refreshSource = if (source.type == StreamType.HLS) {
                            val refresh: suspend (Boolean) -> StreamSource? = { _ ->
                                val refreshedSources = recordingSources(roomId)
                                repository.log(roomId, sessionId, "INFO", "STREAM_REFRESH_REQUESTED",
                                    "重新获取 HLS 线路；这是取流重试时间，不是精确缺失区间")
                                val hlsSources = refreshedSources
                                    .filter { it.type == StreamType.HLS }
                                    .distinctBy { it.url }
                                HlsSourceSelector(container.httpClient).select(hlsSources, room.quality, lastHlsHost)
                                    .also { lastHlsHost = java.net.URI(it.url).host }
                            }
                            refresh
                        } else if (source.type == StreamType.FLV) {
                            val refresh: suspend (Boolean) -> StreamSource? = { receivedMedia ->
                                val refreshedSources = recordingSources(roomId)
                                    .filter { it.type == StreamType.FLV }.distinctBy { it.url }
                                repository.log(roomId, sessionId, "INFO", "STREAM_REFRESH_REQUESTED",
                                    "重新获取 FLV 线路；这是取流重试时间，不是精确缺失区间")
                                com.streamcap.mobile.recording.flv.FlvSourceSelector.select(refreshedSources, lastFlvSource, receivedMedia)
                                    ?.also { lastFlvSource = it }
                            }
                            refresh
                        } else {
                            null
                        },
                    ),
                )
                val recording = ActiveRecording(
                    roomId = roomId,
                    roomName = room.anchorName,
                    platform = room.platform,
                    roomTitle = room.roomTitle,
                    sessionId = sessionId,
                    handle = handle,
                    recorder = selectedRecorder,
                    temporaryFile = temporary,
                    streamType = source.type,
                    outputFormat = actualFormat,
                    storageDirectoryUri = room.storageDirectoryUri ?: settings.storageDirectoryUri,
                    notificationsEnabled = room.notificationsEnabled,
                    stopAtMillis = effectiveStopAt,
                    fileNameTemplate = settings.fileNameTemplate,
                    includeTitleInFileName = settings.includeTitleInFileName,
                    removeEmojiFromFileName = settings.removeEmojiFromFileName,
                    danmakuController = danmakuController,
                )
                danmakuController?.start(DanmakuConnectContext(
                    sessionId = sessionId, platform = room.platform, roomUrl = room.normalizedUrl,
                    cookie = container.credentialStore.get(room.platform)?.cookie,
                    mediaStartedElapsedMs = mediaStart,
                ))
                active[roomId] = recording
                pendingDanmaku = null
                enforceNetworkPolicy()
                starting.remove(roomId)
                acquireWakeLock()
                scheduleStop(recording)
                repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.RECORDING)
                observeRecording(recording)
            } catch (error: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { pendingDanmaku?.stop("start-failed") }
                }
                if (error is kotlinx.coroutines.CancellationException) {
                    starting.remove(roomId)
                    throw error
                }
                if (error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) {
                    repository.setRoomTaskStatus(roomId, LiveStatus.OFFLINE, roomTaskStatus(roomId))
                    repository.log(roomId, createdSessionId, "INFO", "LIVE_ENDED", "主播已下播，停止获取直播源")
                    starting.remove(roomId)
                    retryCounts.remove(roomId)
                    sourceAttempts.remove(roomId)
                    stopIfIdle()
                    return@launch
                }
                if (error is com.streamcap.mobile.network.NetworkPolicyException || pauseRequested.contains(roomId)) {
                    createdSessionId?.let { id ->
                        repository.session(id)?.let { session ->
                            repository.updateSession(session.copy(status = TaskStatus.PAUSED,
                                endedAt = System.currentTimeMillis(), errorMessage = error.message))
                        }
                    }
                    starting.remove(roomId)
                    pauseRecording(roomId)
                    repository.log(roomId, null, "WARN", "NETWORK_POLICY_PAUSED", error.message ?: "录制已暂停")
                    return@launch
                }
                val errorMessage = error.message ?: "启动录制失败"
                val retryCount = retryCounts[roomId] ?: 0
                if (!sessionCreated && retryCount == 0) {
                    repository.createStartFailureSession(room, errorMessage)
                }
                repository.setRoomTaskStatus(
                    roomId,
                    room.liveStatus.takeIf { it != LiveStatus.UNKNOWN && it != LiveStatus.CHECKING } ?: LiveStatus.ERROR,
                    TaskStatus.FAILED,
                )
                repository.log(roomId, null, "ERROR", "RECORDING_START_FAILED", errorMessage)
                if (room.notificationsEnabled) {
                    notifications.notifyRoom(
                        roomId,
                        notifications.recordingNotification(
                            roomId,
                            room.anchorName,
                            errorMessage,
                            ongoing = false,
                            recoverySessionId = createdSessionId,
                        ),
                    )
                }
                val stopTimeNotReached = stopAtMillis?.let { it > System.currentTimeMillis() } ?: true
                val shouldRetry = !stopRequested.contains(roomId) &&
                    !pauseRequested.contains(roomId) &&
                    stopTimeNotReached &&
                    (retryCount < MAX_START_RETRIES || room.scheduledMonitoringEnabled) &&
                    repository.room(roomId)?.enabled == true
                if (shouldRetry) {
                    retryCounts[roomId] = retryCount + 1
                    sourceAttempts[roomId] = (sourceAttempts[roomId] ?: 0) + 1
                    repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.RECONNECTING)
                    repository.log(
                        roomId,
                        null,
                        "INFO",
                        "RECORDING_RETRY",
                        "录制启动异常，将在刷新直播地址后进行第 ${retryCount + 1} 次重试",
                    )
                    scheduleRetry(roomId, stopAtMillis, retryCount)
                    starting.remove(roomId)
                } else {
                    starting.remove(roomId)
                    retryCounts.remove(roomId)
                    sourceAttempts.remove(roomId)
                    stopIfIdle()
                }
            }
        }
    }

    private suspend fun observeRecording(recording: ActiveRecording) {
        var lastPersistAt = 0L
        var lastNotificationAt = 0L
        var lastRoomStateAt = 0L
        var lastRoomState: RecorderState? = null
        val terminal = recording.recorder.observe(recording.handle)
            .onEach { progress ->
                val now = System.currentTimeMillis()
                if (progress.state == RecorderState.RECORDING && container.recordingStorage.recordingRoot().usableSpace < MIN_RUNTIME_FREE_BYTES) {
                    repository.log(recording.roomId, recording.sessionId, "WARN", "STORAGE_LOW", "可用空间低于 128 MB，正在停止并保存已有内容")
                    recording.stopReason = StopReason.STORAGE_LOW
                    recording.recorder.stop(recording.handle, StopReason.STORAGE_LOW)
                }
                if (recording.notificationsEnabled && now - lastNotificationAt >= 5_000L) {
                    notifications.notifyRoom(
                        recording.roomId,
                        notifications.recordingNotification(
                            recording.roomId,
                            recording.roomName,
                            if (progress.state == RecorderState.RECONNECTING) "断流重试中 · 已录制 ${formatBytes(progress.bytesWritten)}"
                            else "已录制 ${formatBytes(progress.bytesWritten)} · ${formatDuration(progress.durationSeconds)}",
                            ongoing = progress.state == RecorderState.STARTING || progress.state == RecorderState.RECORDING || progress.state == RecorderState.RECONNECTING,
                        ),
                    )
                    lastNotificationAt = now
                }
                checkpointCompletedParts(recording, progress.completedOutputFiles)
                if (progress.state != lastRoomState && now - lastRoomStateAt >= 5_000L &&
                    (progress.state == RecorderState.RECONNECTING || progress.state == RecorderState.RECORDING)) {
                    repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, progress.state.toTaskStatus())
                    lastRoomState = progress.state
                    lastRoomStateAt = now
                }
                if (now - lastPersistAt >= 5_000) {
                    repository.session(recording.sessionId)?.let { session ->
                        repository.updateSession(
                            session.copy(
                                status = progress.state.toTaskStatus(),
                                totalBytes = progress.bytesWritten,
                                durationSeconds = progress.durationSeconds,
                                errorMessage = progress.errorMessage,
                            ),
                        )
                    }
                    lastPersistAt = now
                }
            }
            .first { it.state in TERMINAL_STATES }

        repository.log(recording.roomId, recording.sessionId, "INFO", "RECORDING_STOP_REASON",
            "time=${System.currentTimeMillis()} sessionId=${recording.sessionId} reason=${recording.stopReason ?: when {
                terminal.liveEnded -> "LIVE_ENDED"
                terminal.state == RecorderState.FAILED -> "STREAM_RETRY_EXHAUSTED"
                else -> "STREAM_ENDED"
            }} bytes=${terminal.bytesWritten} part=${terminal.outputFiles.size}")

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
        // An explicit pause wins over a transport error raised while cancelling the HTTP call.
        val userPaused = pauseRequested.contains(recording.roomId)
        val persistedOutputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
        val persistedPublishedDuration = publishedDurationSeconds(persistedOutputUris)
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
        val intentionalStop = terminal.state == RecorderState.STOPPED ||
            stopRequested.contains(recording.roomId) ||
            pauseRequested.contains(recording.roomId)
        val liveStatus = when {
            terminal.liveEnded -> LiveStatus.OFFLINE
            !intentionalStop -> repository.recordingLiveStatus(recording.roomId)
            else -> currentRoom?.liveStatus ?: LiveStatus.UNKNOWN
        }
        val liveEnded = liveStatus == LiveStatus.OFFLINE && !intentionalStop
        val isHuya = recording.platform in setOf("huya", "虎牙直播")
        val hlsFallback = !isHuya && terminal.retryExhausted && recording.streamType == StreamType.FLV && runCatching {
            val candidates = recordingSources(recording.roomId).filter { it.type == StreamType.HLS }
            candidates.isNotEmpty() && HlsSourceSelector(container.httpClient).select(candidates).url.isNotBlank()
        }.getOrDefault(false)
        val disconnectPaused = terminal.retryExhausted && !liveEnded && !hlsFallback
        val paused = userPaused || disconnectPaused
        val stopTimeNotReached = recording.stopAtMillis?.let { it > System.currentTimeMillis() } ?: true
        // A live transport may end at any duration, including manual long recordings.
        val liveDisconnect = terminal.state == RecorderState.COMPLETED &&
            terminal.bytesWritten > 0L &&
            !intentionalStop &&
            !liveEnded && stopTimeNotReached &&
            currentRoom?.enabled == true
        val terminalError = terminal.errorMessage ?: "没有写入有效媒体数据"
        val retryCount = retryCounts[recording.roomId] ?: 0
        val shouldRetry = terminal.state == RecorderState.FAILED &&
            (!terminal.retryExhausted || hlsFallback) &&
            !liveEnded &&
            !stopRequested.contains(recording.roomId) &&
            !pauseRequested.contains(recording.roomId) &&
            stopTimeNotReached &&
            repository.room(recording.roomId)?.let { room ->
                room.enabled && (retryCount < MAX_START_RETRIES || room.scheduledMonitoringEnabled)
            } == true
        // 断流前已经写入的媒体必须保留。先发布当前有效段，再刷新线路继续录制。
        val publishBeforeRetry = (shouldRetry || liveDisconnect || hlsFallback) && terminal.bytesWritten > 0L
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

        val publishResult = heavyPublicationPermit.withPermit { withContext(Dispatchers.IO) { runCatching {
            val extension = recording.outputFormat.lowercase()
            val now = LocalDateTime.now()
            val baseName = RecordingNameFormatter.format(
                template = recording.fileNameTemplate,
                includeTitle = recording.includeTitleInFileName,
                removeEmoji = recording.removeEmojiFromFileName,
                context = RecordingNameContext(
                    platform = recording.platform,
                    anchorName = recording.roomName,
                    title = recording.roomTitle,
                    time = now,
                ),
            )
            val existingFiles = terminal.outputFiles
                .filter { it.exists() && it.length() > 0 && it.absolutePath !in recording.checkpointedFiles }
                .ifEmpty { listOf(recording.temporaryFile).filter { it.exists() && it.length() > 0 && it.absolutePath !in recording.checkpointedFiles } }
            val files = if (recording.outputFormat == "FLV" && existingFiles.isNotEmpty()) {
                existingFiles.filter(FlvFileInspector::hasMediaTags).also { playableFiles ->
                    existingFiles.filterNot(playableFiles::contains).forEach(File::delete)
                    check(playableFiles.isNotEmpty()) { "FLV 中没有完整的音视频数据，未保存空文件" }
                }
            } else {
                existingFiles
            }
            // A forced stop can leave the final transport packet half-written. Trim it before
            // muxing so one broken tail cannot invalidate an otherwise playable long recording.
            files.forEach { file ->
                if (recording.outputFormat == "FLV" || file.extension.equals("flv", true)) {
                    RecordingFileRepair.repairFlv(file)
                } else if (file.extension.equals("ts", true) || file.name.contains(".ts.")) {
                    RecordingFileRepair.repairTransportStream(file)
                }
            }
            var muxedDurationSeconds = 0L
            val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
            var publishedDuration = publishedDurationSeconds(outputUris)
            files.forEachIndexed { index, file ->
                val indexedOutput = outputUris.isNotEmpty() || files.size > 1
                val partSuffix = if (indexedOutput) "_${(outputUris.size + 1).toString().padStart(3, '0')}" else ""
                val publishFile = if (recording.outputFormat == "MP4") {
                    repository.updateSession(session.copy(
                        status = TaskStatus.FINALIZING,
                        outputUri = outputUris.firstOrNull(),
                        outputUris = outputUris.toList(),
                        errorMessage = "正在修复索引${if (files.size > 1) "（第 ${index + 1}/${files.size} 段）" else ""}",
                    ))
                    File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                        .also { target ->
                            val tracks = RecordingMp4Remuxer.convert(file, target) { ffmpegError ->
                                    repository.log(
                                        recording.roomId,
                                        recording.sessionId,
                                        "WARN",
                                        "FFMPEG_MP4_MUX_FAILED",
                                        "FFmpeg 封装失败，已切换系统封装器：${ffmpegError.message}",
                                    )
                                }
                            muxedDurationSeconds += tracks.durationSeconds
                            if (tracks.audioTracks == 0) {
                                repository.log(
                                    recording.roomId,
                                    recording.sessionId,
                                    "WARN",
                                    "RECORDING_AUDIO_TRACK_MISSING",
                                    "MP4 封装完成但没有检测到音频轨道，可能是平台线路只返回视频或音频编码不受设备支持",
                                )
                            }
                        }
                } else file
                repository.updateSession(session.copy(
                    status = TaskStatus.FINALIZING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    errorMessage = "正在保存${if (files.size > 1) "（第 ${index + 1}/${files.size} 段）" else ""}",
                ))
                val outputUri = container.recordingStorage.publish(
                    publishFile,
                    "${baseName}${partSuffix}.$extension",
                    recording.outputFormat.mimeType(),
                    recording.storageDirectoryUri,
                    recording.roomName,
                    retainTemporary = true,
                    publicationKey = file.absolutePath,
                )
                outputUris += outputUri
                // Persist every successfully published part immediately. If the process dies
                // while a later part is being muxed, the earlier parts remain playable and visible.
                publishedDuration += publishedDurationSeconds(listOf(outputUri))
                repository.updateSession(session.copy(
                    status = TaskStatus.FINALIZING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = publishedDuration.takeIf { it > 0L }
                        ?: (muxedDurationSeconds.takeIf { it > 0L } ?: terminal.durationSeconds),
                    errorMessage = "已保存 ${outputUris.size}/${files.size} 个分段",
                ))
                if (publishFile != file) publishFile.delete()
                file.delete()
                container.recordingStorage.acknowledgePublication(file.absolutePath)
            }
            // Media duration is queried once for the existing prefix and once per new part.
            val finalDurationSeconds = publishedDuration.takeIf { it > 0L }
                ?: muxedDurationSeconds.takeIf { recording.outputFormat == "MP4" && it > 0L }
                ?: terminal.durationSeconds
            repository.updateSession(
                session.copy(
                    status = if (paused) TaskStatus.PAUSED else TaskStatus.COMPLETED,
                    endedAt = System.currentTimeMillis(),
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = finalDurationSeconds,
                    errorMessage = if (disconnectPaused) "断流已暂停，已保存当前内容" else null,
                ),
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
                    outputUris.size == 1 -> "录制文件已保存"
                    else -> "录制完成，已保存 ${outputUris.size} 个分段文件"
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
                                "录制完成 · ${formatBytes(terminal.bytesWritten)}"
                            },
                            ongoing = false,
                            recoverySessionId = recording.sessionId,
                    ),
                )
            }
        } } }.onFailure { error ->
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
    private suspend fun checkpointCompletedParts(recording: ActiveRecording, completed: List<File>) = withContext(Dispatchers.IO) {
        if (completed.isEmpty()) return@withContext
        if (completed.none { it.absolutePath !in recording.checkpointedFiles }) return@withContext
        heavyPublicationPermit.withPermit {
        var session = repository.session(recording.sessionId) ?: return@withPermit
        val room = repository.room(recording.roomId) ?: return@withPermit
        val settings = container.preferences.settings.first()
        val baseName = RecordingNameFormatter.format(
            template = recording.fileNameTemplate,
            includeTitle = recording.includeTitleInFileName,
            removeEmoji = recording.removeEmojiFromFileName,
            context = RecordingNameContext(
                platform = recording.platform,
                anchorName = recording.roomName,
                title = recording.roomTitle,
                time = LocalDateTime.now(),
            ),
        )
        val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
        completed.distinctBy { it.absolutePath }.forEach { file ->
            val key = file.absolutePath
            if (key in recording.checkpointedFiles || !file.exists() || file.length() <= 0L) return@forEach
            val expectedPart = outputUris.size + 1
            if (com.streamcap.mobile.storage.TemporaryRecordingParts.index(recording.temporaryFile, file) != expectedPart) return@withPermit
            try {
                withContext(Dispatchers.IO) {
                    if (recording.outputFormat == "FLV" || file.extension.equals("flv", true)) {
                        RecordingFileRepair.repairFlv(file)
                    } else if (file.extension.equals("ts", true) || file.name.contains(".ts.")) {
                        RecordingFileRepair.repairTransportStream(file)
                    }
                }
                val index = outputUris.size + 1
                val extension = recording.outputFormat.lowercase()
                val publishFile: File
                if (recording.outputFormat == "MP4") {
                    publishFile = File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                    RecordingMp4Remuxer.convert(file, publishFile)
                } else {
                    publishFile = file
                }
                val uri = withContext(Dispatchers.IO) {
                    container.recordingStorage.publish(
                        publishFile,
                        "${baseName}_${index.toString().padStart(3, '0')}.$extension",
                        recording.outputFormat.mimeType(),
                        recording.storageDirectoryUri ?: room.storageDirectoryUri ?: settings.storageDirectoryUri,
                        recording.roomName,
                        retainTemporary = true,
                        publicationKey = file.absolutePath,
                    )
                }
                outputUris += uri
                session = session.copy(
                    status = TaskStatus.RECORDING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    errorMessage = "录制中已保存 ${outputUris.size} 个分段",
                )
                repository.updateSession(session)
                // Never remove recovery input before its published URI is durable.
                recording.checkpointedFiles += key
                if (publishFile != file) publishFile.delete()
                file.delete()
                container.recordingStorage.acknowledgePublication(file.absolutePath)
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "INFO",
                    "RECORDING_CHECKPOINT_SAVED",
                    "录制中已保存第 ${outputUris.size} 个分段",
                )
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "WARN",
                    "RECORDING_CHECKPOINT_FAILED",
                    "分段暂未发布，将在录制结束或恢复时重试：${failure.message}",
                )
                // Keep publication a contiguous prefix. Publishing later parts
                // first would reorder playback and invalidate tail indices.
                return@withPermit
            }
        }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        monitoringActive = false
        stopIfIdle()
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

    private suspend fun recordingSources(roomId: Long): List<StreamSource> {
        if (repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) {
            throw com.streamcap.mobile.recording.engine.LiveStreamEndedException()
        }
        val sources = try {
            repository.streams(roomId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) {
                throw com.streamcap.mobile.recording.engine.LiveStreamEndedException()
            }
            throw error
        }
        if (sources.isEmpty() && repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) {
            throw com.streamcap.mobile.recording.engine.LiveStreamEndedException()
        }
        return sources
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

    private fun pauseRecording(roomId: Long, reason: StopReason = StopReason.PAUSE) {
        retries.cancel(roomId)
        pauseRequested.add(roomId)
        resumeRequested.remove(roomId)
        retryCounts.remove(roomId)
        sourceAttempts.remove(roomId)
        val recording = active[roomId]
        if (recording != null) {
            recording.stopReason = reason
            recording.timerJob?.cancel()
            serviceScope.launch { recording.recorder.stop(recording.handle, reason) }
        } else {
            serviceScope.launch {
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

    private suspend fun recoverInterruptedTasksNow(sessionId: Long? = null) = recoveryMutex.withLock {
        // Only sessions left by a previous service instance need crash recovery.
        // A second ACTION_MONITOR must never remux an actively written file.
        val sessions = (repository.activeSessions() + repository.failedPublications()).filter {
            (sessionId == null || it.id == sessionId) &&
                (it.status == TaskStatus.FAILED || (it.startedAt ?: 0L) < serviceCreatedAt) &&
                !active.containsKey(it.roomId) && !starting.contains(it.roomId)
        }
        sessions.forEach { session ->
            val recovered = runCatching { recoverInterruptedSession(session) }
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

    private suspend fun recoverInterruptedSession(session: RecordingSessionEntity): Boolean = withContext(Dispatchers.IO) { heavyPublicationPermit.withPermit {
        val first = session.temporaryPath?.let(::File) ?: return@withPermit false
        val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
        val files = temporaryFamily(first).filter {
            it.exists() && it.length() > 0L &&
                (com.streamcap.mobile.storage.TemporaryRecordingParts.index(first, it) ?: 0) > outputUris.size
        }
        // Files may remain after the URI commit but before source cleanup. Their
        // original indices distinguish them from genuinely unpublished recovery input.
        val recoveredBytes = maxOf(session.totalBytes, files.sumOf { it.length() })
        // A process interruption can leave durationSeconds at the last active tail
        // segment even though outputUris already contains many published checkpoints.
        // Rebuild the displayed duration from all published media before adding new files.
        val publishedDuration = publishedDurationSeconds(outputUris)
        if (files.isEmpty()) {
            if (outputUris.isEmpty()) return@withPermit false
            repository.updateSession(session.copy(
                status = TaskStatus.COMPLETED,
                endedAt = System.currentTimeMillis(),
                outputUri = outputUris.first(),
                outputUris = outputUris.toList(),
                durationSeconds = publishedDuration.takeIf { it > 0L } ?: session.durationSeconds,
                errorMessage = "应用进程中断，已保留已保存的 ${outputUris.size} 个分段",
            ))
            return@withPermit true
        }
        val room = repository.room(session.roomId) ?: return@withPermit false
        val settings = container.preferences.settings.first()
        val baseName = RecordingNameFormatter.format(
            template = settings.fileNameTemplate,
            includeTitle = settings.includeTitleInFileName,
            removeEmoji = settings.removeEmojiFromFileName,
            context = RecordingNameContext(room.platform, session.roomName, session.roomTitle, LocalDateTime.now()),
        )
        var duration = publishedDuration
        val existingPartCount = outputUris.size
        val totalParts = existingPartCount + files.size
        files.forEach { file ->
            check(com.streamcap.mobile.storage.TemporaryRecordingParts.index(first, file) == outputUris.size + 1) {
                "分段序号不连续，未发布的源文件已保留"
            }
            if (session.streamType == StreamType.FLV || file.extension.equals("flv", true)) {
                RecordingFileRepair.repairFlv(file)
            } else {
                RecordingFileRepair.repairTransportStream(file)
            }
            val suffix = if (totalParts > 1) "_${(outputUris.size + 1).toString().padStart(3, '0')}" else ""
            val published = if (session.containerFormat.equals("mp4", true)) {
                val target = File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                val tracks = RecordingMp4Remuxer.convert(file, target)
                duration += tracks.durationSeconds
                container.recordingStorage.publish(target, "$baseName$suffix.mp4", "video/mp4", room.storageDirectoryUri ?: settings.storageDirectoryUri, session.roomName, retainTemporary = true, publicationKey = file.absolutePath)
                    .also { target.delete() }
            } else {
                val extension = session.containerFormat.lowercase()
                container.recordingStorage.publish(file, "$baseName$suffix.$extension", extension.mimeType(), room.storageDirectoryUri ?: settings.storageDirectoryUri, session.roomName, retainTemporary = true, publicationKey = file.absolutePath)
            }
            outputUris += published
            if (!session.containerFormat.equals("mp4", true)) duration += publishedDurationSeconds(listOf(published))
            repository.updateSession(session.copy(
                status = TaskStatus.FINALIZING,
                outputUri = outputUris.firstOrNull(),
                outputUris = outputUris.toList(),
                totalBytes = recoveredBytes,
                durationSeconds = duration.takeIf { it > 0L } ?: session.durationSeconds,
                errorMessage = "应用进程中断，已自动保存 ${outputUris.size} 个分段",
            ))
            file.delete()
            container.recordingStorage.acknowledgePublication(file.absolutePath)
        }
        repository.updateSession(session.copy(
            status = TaskStatus.COMPLETED,
            endedAt = System.currentTimeMillis(),
            outputUri = outputUris.firstOrNull(),
            outputUris = outputUris,
            totalBytes = recoveredBytes,
            durationSeconds = duration.takeIf { it > 0L } ?: session.durationSeconds,
            errorMessage = "应用进程中断，已自动保存 ${outputUris.size} 个分段",
        ))
        outputUris.isNotEmpty()
    }
    }

    private suspend fun publishedDurationSeconds(uris: List<String>): Long = withContext(Dispatchers.IO) {
        uris.sumOf { raw ->
            runCatching {
                contentResolver.query(
                    Uri.parse(raw),
                    arrayOf(MediaStore.Video.VideoColumns.DURATION),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) / 1_000L else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }
    }

    private fun temporaryFamily(first: File): List<File> {
        val marker = first.name.indexOf(".ts.part").takeIf { it >= 0 }
            ?: first.name.indexOf(".flv.part").takeIf { it >= 0 }
        val prefix = marker?.let { first.name.substring(0, it) } ?: first.nameWithoutExtension
        val suffix = marker?.let { first.name.substring(it) } ?: ".${first.extension}"
        val patterns = listOf(
            Regex("^${Regex.escape(prefix)}_(\\d{3})${Regex.escape(suffix)}$"),
            // Compatibility with the pre-fix `name.ts_002.part` naming.
            Regex("^${Regex.escape(first.nameWithoutExtension)}_(\\d{3})\\.${Regex.escape(first.extension)}$"),
        ).distinctBy(Regex::pattern)
        return first.parentFile?.listFiles().orEmpty().mapNotNull { file ->
            val index = if (file.name == first.name) 1 else patterns.firstNotNullOfOrNull {
                it.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }
            index?.let { it to file }
        }.sortedBy { it.first }.map { it.second }
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

    private fun releasePowerLocks() = synchronized(powerLockGuard) {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        if (wakeLock.isHeld) wakeLock.release()
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun acquireWakeLock() = synchronized(powerLockGuard) {
        if (powerLocksClosed) return@synchronized
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        wifiLock?.let { lock -> runCatching { if (!lock.isHeld) lock.acquire() } }
        if (wakeLockRefreshJob == null) {
            wakeLockRefreshJob = serviceScope.launch {
                while (monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending()) {
                    delay(WAKE_LOCK_REFRESH_MILLIS)
                    synchronized(powerLockGuard) {
                        if (!powerLocksClosed && (!powerSavingMonitoring || active.isNotEmpty() || starting.isNotEmpty()) &&
                            (monitoringActive || active.isNotEmpty() || starting.isNotEmpty() || retries.hasPending())) {
                            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                            wifiLock?.let { lock -> runCatching { if (!lock.isHeld) lock.acquire() } }
                        }
                    }
                }
            }
        }
    }

    private fun String.mimeType(): String = when (uppercase()) {
        "FLV" -> "video/x-flv"
        "TS" -> "video/mp2t"
        "MP4" -> "video/mp4"
        "MKV" -> "video/x-matroska"
        else -> "video/mp4"
    }

    private fun RecorderState.toTaskStatus(): TaskStatus = when (this) {
        RecorderState.STARTING -> TaskStatus.STARTING
        RecorderState.RECORDING -> TaskStatus.RECORDING
        RecorderState.RECONNECTING -> TaskStatus.RECONNECTING
        RecorderState.COMPLETED, RecorderState.STOPPED -> TaskStatus.FINALIZING
        RecorderState.FAILED -> TaskStatus.FAILED
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1_024.0)
    }

    private fun formatDuration(seconds: Long): String = "%02d:%02d:%02d".format(
        seconds / 3_600,
        (seconds % 3_600) / 60,
        seconds % 60,
    )

    companion object {
        private val heavyPublicationPermit = Semaphore(1)
        private const val MIN_FREE_BYTES = 512L * 1_048_576L
        private const val MIN_RUNTIME_FREE_BYTES = 128L * 1_048_576L
        private const val ACTION_START = "com.streamcap.mobile.action.START_RECORDING"
        private const val ACTION_STOP = "com.streamcap.mobile.action.STOP_RECORDING"
        private const val ACTION_DELETE_ROOM = "com.streamcap.mobile.action.DELETE_ROOM"
        private const val ACTION_PAUSE = "com.streamcap.mobile.action.PAUSE_RECORDING"
        private const val ACTION_RECOVER = "com.streamcap.mobile.action.RECOVER"
        private const val ACTION_MONITOR = "com.streamcap.mobile.action.MONITOR"
        private const val ACTION_STOP_MONITOR = "com.streamcap.mobile.action.STOP_MONITOR"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_STOP_AT = "stop_at"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        private const val WAKE_LOCK_REFRESH_MILLIS = 8 * 60 * 1_000L
        private const val MAX_START_RETRIES = 4
        private const val MONITOR_POLL_MILLIS = 15_000L
        private const val POWER_SAVING_POLL_MILLIS = 60_000L
        private const val MIN_MONITOR_INTERVAL_SECONDS = 15
        private const val RECOVERY_PREFS = "service_recovery"
        private const val RECOVERY_GENERATION = "generation"
        private const val RESTART_REQUEST_CODE = 4107
        private const val RESTART_DELAY_MILLIS = 5_000L
        private val TERMINAL_STATES = setOf(
            RecorderState.COMPLETED,
            RecorderState.STOPPED,
            RecorderState.FAILED,
        )

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

        fun stopMonitoring(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP_MONITOR),
            )
        }
    }
}
