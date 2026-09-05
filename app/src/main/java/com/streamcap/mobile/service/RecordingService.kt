package com.streamcap.mobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.MAX_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.recording.engine.RecorderState
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.recording.flv.DirectHttpRecorder
import com.streamcap.mobile.recording.flv.FlvFileInspector
import com.streamcap.mobile.recording.hls.HlsRecorder
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import com.streamcap.mobile.recording.ffmpeg.FfmpegHlsRecorder
import com.streamcap.mobile.recording.ffmpeg.FfmpegMp4Muxer
import com.streamcap.mobile.recording.mp4.Mp4Muxer
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
import kotlinx.coroutines.launch
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
        var timerJob: Job? = null,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serviceCreatedAt = System.currentTimeMillis()
    private val active = ConcurrentHashMap<Long, ActiveRecording>()
    private val starting = ConcurrentHashMap.newKeySet<Long>()
    private val stopRequested = ConcurrentHashMap.newKeySet<Long>()
    private val pauseRequested = ConcurrentHashMap.newKeySet<Long>()
    private val retryCounts = ConcurrentHashMap<Long, Int>()
    private val sourceAttempts = ConcurrentHashMap<Long, Int>()
    private var monitorJob: Job? = null
    private var monitoringActive = false
    private lateinit var notifications: RecordingNotifications
    private lateinit var directRecorder: DirectHttpRecorder
    private lateinit var hlsRecorder: HlsRecorder
    private lateinit var ffmpegHlsRecorder: FfmpegHlsRecorder
    private lateinit var wakeLock: PowerManager.WakeLock
    private var wakeLockRefreshJob: Job? = null

    private val container get() = (application as StreamCapApplication).container
    private val repository get() = container.repository

    override fun onCreate() {
        super.onCreate()
        notifications = RecordingNotifications(this)
        directRecorder = DirectHttpRecorder(container.httpClient, serviceScope)
        hlsRecorder = HlsRecorder(container.httpClient, serviceScope)
        ffmpegHlsRecorder = FfmpegHlsRecorder(serviceScope)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "StreamCap:Recording",
        ).apply { setReferenceCounted(false) }
        startForeground(
            RecordingNotifications.SERVICE_NOTIFICATION_ID,
            notifications.serviceNotification("正在准备任务"),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            recoverInterruptedTasks()
            prepareMonitoring()
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                val roomId = intent.getLongExtra(EXTRA_ROOM_ID, -1)
                val stopAt = intent.getLongExtra(EXTRA_STOP_AT, -1).takeIf { it > 0 }
                if (roomId > 0) {
                    stopRequested.remove(roomId)
                    pauseRequested.remove(roomId)
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

            ACTION_RECOVER -> recoverInterruptedTasks()

            ACTION_MONITOR -> prepareMonitoring()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        active.values.forEach { recording ->
            serviceScope.launch { recording.recorder.stop(recording.handle, StopReason.SERVICE_DESTROYED) }
        }
        wakeLockRefreshJob?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            monitoringActive = true
            try {
                while (true) {
                    val rooms = repository.monitoredRooms()
                    if (rooms.isEmpty()) break
                    getSystemService(android.app.NotificationManager::class.java).notify(
                        RecordingNotifications.SERVICE_NOTIFICATION_ID,
                        notifications.serviceNotification(
                            if (active.isEmpty()) "正在监控 ${rooms.size} 个直播间" else "${active.size} 个任务录制中",
                        ),
                    )
                    rooms.forEach { room ->
                        if (room.taskStatus == TaskStatus.PAUSED || active.containsKey(room.id) || starting.contains(room.id)) {
                            return@forEach
                        }
                        val checkIntervalMillis = MIN_MONITOR_INTERVAL_SECONDS * 1_000L
                        val checkDue = room.lastCheckAt?.let { System.currentTimeMillis() - it >= checkIntervalMillis } ?: true
                        if (!checkDue) return@forEach
                        runCatching { repository.refresh(room.id) }
                            .onSuccess { status ->
                                if (status == LiveStatus.LIVE) startRecording(room.id, null)
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
                    delay(MONITOR_POLL_MILLIS)
                }
            } catch (error: Throwable) {
                repository.log(null, null, "ERROR", "MONITOR_LOOP_FAILED", error.message ?: "监控循环异常")
                // 监控异常不能让前台服务永久退出；下一轮仍会重新读取直播间并检测。
                serviceScope.launch {
                    delay(MONITOR_POLL_MILLIS)
                    if (repository.monitoredRooms().isNotEmpty()) startMonitoring()
                }
            } finally {
                monitoringActive = false
                monitorJob = null
                stopIfIdle()
            }
        }
    }

    private fun prepareMonitoring() {
        serviceScope.launch {
            repository.activeSessions().filter { (it.startedAt ?: Long.MAX_VALUE) < serviceCreatedAt }.forEach { session ->
                if (active.values.none { it.sessionId == session.id }) {
                    repository.updateSession(session.copy(
                        status = TaskStatus.FAILED,
                        endedAt = serviceCreatedAt,
                        errorMessage = "上次录制进程已结束，可尝试预览保留的文件",
                    ))
                }
            }
            repository.monitoredRooms().forEach { room ->
                stopRequested.remove(room.id)
                pauseRequested.remove(room.id)
            }
            // 应用启动/恢复时立即扫描一次，不能因为上次检查时间较新而跳过首轮检测。
            scanMonitoredRoomsNow()
            startMonitoring()
        }
    }

    private suspend fun scanMonitoredRoomsNow() {
        repository.monitoredRooms().forEach { room ->
            if (room.taskStatus == TaskStatus.PAUSED || active.containsKey(room.id) || starting.contains(room.id)) return@forEach
            runCatching { repository.refresh(room.id) }
                .onSuccess { status ->
                    if (status == LiveStatus.LIVE) startRecording(room.id, null)
                }
                .onFailure { error ->
                    repository.log(room.id, null, "ERROR", "STARTUP_SCAN_FAILED", error.message ?: "启动扫描失败")
                }
        }
    }

    private fun startRecording(roomId: Long, stopAtMillis: Long?) {
        if (stopRequested.contains(roomId) || pauseRequested.contains(roomId)) return
        if (active.containsKey(roomId) || !starting.add(roomId)) return
        serviceScope.launch {
            val room = repository.room(roomId) ?: run {
                starting.remove(roomId)
                return@launch
            }
            if (!room.enabled) {
                starting.remove(roomId)
                return@launch
            }
            var sessionCreated = false
            try {
                val settings = container.preferences.settings.first()
                val availableSources = repository.streams(roomId)
                val requestedFormat = room.outputFormat.uppercase()
                val preferHuyaFlv = requestedFormat == "MP4" && room.platform in setOf("huya", "虎牙直播") &&
                    availableSources.any { it.type == StreamType.FLV }
                val preferredCandidates = when (requestedFormat) {
                    "FLV" -> availableSources.filter { it.type == StreamType.FLV }
                    "MP4" -> availableSources.filter { it.type == if (preferHuyaFlv) StreamType.FLV else StreamType.HLS }
                    "TS" -> availableSources.filter { it.type == StreamType.HLS }
                    else -> availableSources.filter { it.type == if (room.flvDirectDownload) StreamType.FLV else StreamType.HLS }
                }
                // MP4 is an output container; Huya uses the desktop resolver's FLV preference.
                val candidates = preferredCandidates
                    .ifEmpty { availableSources.filter { it.type == StreamType.HLS || it.type == StreamType.FLV } }
                    .distinctBy { it.url }
                val initialSource = candidates.getOrNull(
                    (sourceAttempts[roomId] ?: 0).mod(candidates.size.coerceAtLeast(1)),
                ) ?: error(if (availableSources.isEmpty()) "主播当前未开播，或平台没有返回可用直播源" else "当前线路不提供 $requestedFormat 格式")
                val source = if (initialSource.type == StreamType.HLS) {
                    try {
                        HlsSourceSelector(container.httpClient).select(candidates.filter { it.type == StreamType.HLS })
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (requestedFormat != "MP4") throw failure
                        availableSources.firstOrNull { it.type == StreamType.FLV } ?: throw failure
                    }
                } else initialSource
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
                val sessionId = repository.createSession(
                    room = room,
                    source = source,
                    temporaryPath = temporary.path,
                    stopAtMillis = stopAtMillis,
                    outputFormat = actualFormat,
                )
                sessionCreated = true
                repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.STARTING)
                repository.log(roomId, sessionId, "INFO", "RECORDING_START", "开始录制 ${source.type}")

                // Persist HLS segments before remuxing the finished files to MP4.
                val selectedRecorder: Recorder = if (source.type == StreamType.HLS) {
                    hlsRecorder
                } else {
                    directRecorder
                }
                val handle = selectedRecorder.start(
                    RecordingRequest(
                        url = source.url,
                        headers = source.headers,
                        streamType = source.type,
                        destination = temporary,
                        // 旧数据库可能仍保存 1 分钟等历史值，录制入口再次兜底到 30 分钟。
                        segmentSeconds = room.segmentSeconds
                            .coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS)
                            .takeIf { room.segmentEnabled },
                        // HLS 签名过期与 CDN 断流和是否开启自动监控无关，手动录制也必须能刷新线路。
                        refreshSource = if (source.type == StreamType.HLS) {
                            suspend {
                                val refreshedSources = repository.streams(roomId)
                                    .filter { it.type == StreamType.HLS }
                                    .distinctBy { it.url }
                                HlsSourceSelector(container.httpClient).select(refreshedSources)
                            }
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
                    stopAtMillis = stopAtMillis,
                    fileNameTemplate = settings.fileNameTemplate,
                    includeTitleInFileName = settings.includeTitleInFileName,
                    removeEmojiFromFileName = settings.removeEmojiFromFileName,
                )
                active[roomId] = recording
                starting.remove(roomId)
                acquireWakeLock()
                scheduleStop(recording)
                repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.RECORDING)
                observeRecording(recording)
            } catch (error: Throwable) {
                starting.remove(roomId)
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
                    delay(retryDelayMillis(retryCount))
                    startRecording(roomId, stopAtMillis)
                } else {
                    retryCounts.remove(roomId)
                    sourceAttempts.remove(roomId)
                    stopIfIdle()
                }
            }
        }
    }

    private suspend fun observeRecording(recording: ActiveRecording) {
        var lastPersistAt = 0L
        val terminal = recording.recorder.observe(recording.handle)
            .onEach { progress ->
                val now = System.currentTimeMillis()
                if (recording.notificationsEnabled) {
                    notifications.notifyRoom(
                        recording.roomId,
                        notifications.recordingNotification(
                            recording.roomId,
                            recording.roomName,
                            "已录制 ${formatBytes(progress.bytesWritten)} · ${formatDuration(progress.durationSeconds)}",
                            ongoing = progress.state == RecorderState.STARTING || progress.state == RecorderState.RECORDING,
                        ),
                    )
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

        recording.timerJob?.cancel()
        repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.FINALIZING)
        val session = repository.session(recording.sessionId) ?: return finishActive(recording.roomId)
        val paused = pauseRequested.contains(recording.roomId) && terminal.state == RecorderState.STOPPED
        if (paused && terminal.bytesWritten == 0L) {
            repository.updateSession(
                session.copy(
                    status = TaskStatus.PAUSED,
                    endedAt = System.currentTimeMillis(),
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
        val roomStillLive = terminal.bytesWritten > 0L && confirmRoomStillLive(recording.roomId)
        val segmentSeconds = currentRoom?.let { room ->
            room.segmentSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS)
                .takeIf { room.segmentEnabled }
        }
        val intentionalStop = terminal.state == RecorderState.STOPPED ||
            stopRequested.contains(recording.roomId) ||
            pauseRequested.contains(recording.roomId)
        // HLS 的 ENDLIST 可能只是短时 CDN 播放列表结束。主播仍在线时，丢弃短临时文件并续录，
        // 避免把几十秒线路抖动发布成一条“已完成”录制。
        val shortLiveDisconnect = terminal.state == RecorderState.COMPLETED &&
            terminal.bytesWritten > 0L &&
            terminal.durationSeconds < (segmentSeconds ?: MIN_SEGMENT_SECONDS) &&
            !intentionalStop &&
            (roomStillLive || currentRoom?.liveStatus == LiveStatus.LIVE) &&
            currentRoom?.enabled == true &&
            currentRoom.scheduledMonitoringEnabled
        val terminalError = terminal.errorMessage ?: "没有写入有效媒体数据"
        val retryCount = retryCounts[recording.roomId] ?: 0
        val stopTimeNotReached = recording.stopAtMillis?.let { it > System.currentTimeMillis() } ?: true
        val shouldRetry = terminal.state == RecorderState.FAILED &&
            !stopRequested.contains(recording.roomId) &&
            !pauseRequested.contains(recording.roomId) &&
            stopTimeNotReached &&
            repository.room(recording.roomId)?.let { room ->
                room.enabled && (retryCount < MAX_START_RETRIES || room.scheduledMonitoringEnabled)
            } == true
        // 断流前已经写入的媒体必须保留。先发布当前有效段，再刷新线路继续录制。
        val publishBeforeRetry = (shouldRetry || shortLiveDisconnect) && terminal.bytesWritten > 0L
        if (terminal.state == RecorderState.FAILED || terminal.bytesWritten == 0L) {
            if (shouldRetry && terminal.bytesWritten == 0L) {
                retryCounts[recording.roomId] = retryCount + 1
                sourceAttempts[recording.roomId] = (sourceAttempts[recording.roomId] ?: 0) + 1
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
                delay(retryDelayMillis(retryCount))
                active.remove(recording.roomId)
                if (stopRequested.contains(recording.roomId) || repository.room(recording.roomId)?.enabled != true) {
                    finishActive(recording.roomId)
                } else {
                    startRecording(recording.roomId, recording.stopAtMillis)
                }
                return
            }
            if (publishBeforeRetry) {
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "INFO",
                    "RECORDING_PARTIAL_SAVED",
                    "$terminalError；先保存已录内容，再刷新地址续录",
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
                .filter { it.exists() && it.length() > 0 }
                .ifEmpty { listOf(recording.temporaryFile) }
            val files = if (recording.outputFormat == "FLV") {
                existingFiles.filter(FlvFileInspector::hasMediaTags).also { playableFiles ->
                    existingFiles.filterNot(playableFiles::contains).forEach(File::delete)
                    check(playableFiles.isNotEmpty()) { "FLV 中没有完整的音视频数据，未保存空文件" }
                }
            } else {
                existingFiles
            }
            var muxedDurationSeconds = 0L
            val outputUris = files.mapIndexed { index, file ->
                val partSuffix = if (files.size > 1) "_${(index + 1).toString().padStart(3, '0')}" else ""
                val publishFile = if (recording.outputFormat == "MP4") {
                    File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                        .also { target ->
                            val tracks = runCatching { FfmpegMp4Muxer.convert(file, target) }
                                .getOrElse { ffmpegError ->
                                    repository.log(
                                        recording.roomId,
                                        recording.sessionId,
                                        "WARN",
                                        "FFMPEG_MP4_MUX_FAILED",
                                        "FFmpeg 封装失败，已切换系统封装器：${ffmpegError.message}",
                                    )
                                    target.delete()
                                    Mp4Muxer.convert(file, target)
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
                val outputUri = container.recordingStorage.publish(
                    publishFile,
                    "${baseName}${partSuffix}.$extension",
                    recording.outputFormat.mimeType(),
                    recording.storageDirectoryUri,
                )
                if (publishFile != file) file.delete()
                outputUri
            }
            // MP4 的实际轨道时长比下载循环的累计分片时长更可信，尤其是断流换线路后。
            val publishedDurationSeconds = muxedDurationSeconds.takeIf { recording.outputFormat == "MP4" && it > 0L }
                ?: terminal.durationSeconds
            repository.updateSession(
                session.copy(
                    status = if (paused) TaskStatus.PAUSED else TaskStatus.COMPLETED,
                    endedAt = System.currentTimeMillis(),
                    outputUri = outputUris.first(),
                    outputUris = outputUris,
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = publishedDurationSeconds,
                    errorMessage = null,
                ),
            )
            repository.setRoomTaskStatus(
                recording.roomId,
                LiveStatus.LIVE,
                if (paused) TaskStatus.PAUSED else roomTaskStatus(recording.roomId),
            )
            repository.log(
                recording.roomId,
                recording.sessionId,
                "INFO",
                if (paused) "RECORDING_PAUSED" else "RECORDING_COMPLETED",
                when {
                    paused -> "录制已暂停，当前内容已保存；点击继续后会建立新的录制分段"
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
                            if (paused) {
                                "录制已暂停，当前内容已保存"
                            } else if (publishBeforeRetry) {
                                "当前分段已保存，正在刷新地址续录"
                            } else {
                                "录制完成 · ${formatBytes(terminal.bytesWritten)}"
                            },
                        ongoing = false,
                    ),
                )
            }
        }.onFailure { error ->
            repository.updateSession(
                session.copy(
                    status = TaskStatus.FAILED,
                    endedAt = System.currentTimeMillis(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = terminal.durationSeconds,
                    errorMessage = "文件发布失败：${error.message}",
                ),
            )
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, roomTaskStatus(recording.roomId))
        }
        if (publishResult.isSuccess && publishBeforeRetry) {
            retryCounts[recording.roomId] = 0
            sourceAttempts[recording.roomId] = (sourceAttempts[recording.roomId] ?: 0) + 1
            repository.setRoomTaskStatus(recording.roomId, LiveStatus.LIVE, TaskStatus.RECONNECTING)
            delay(retryDelayMillis(retryCounts[recording.roomId] ?: 0))
            active.remove(recording.roomId)
            if (stopRequested.contains(recording.roomId) || repository.room(recording.roomId)?.enabled != true) {
                finishActive(recording.roomId)
            } else {
                startRecording(recording.roomId, recording.stopAtMillis)
            }
            return
        }
        if (paused) pauseRequested.remove(recording.roomId)
        retryCounts.remove(recording.roomId)
        sourceAttempts.remove(recording.roomId)
        finishActive(recording.roomId)
    }

    private fun scheduleStop(recording: ActiveRecording) {
        val stopAt = recording.stopAtMillis ?: return
        recording.timerJob = serviceScope.launch {
            delay((stopAt - System.currentTimeMillis()).coerceAtLeast(0))
            recording.recorder.stop(recording.handle, StopReason.TIMER)
            repository.log(recording.roomId, recording.sessionId, "INFO", "TIMER_STOP", "定时停止已触发")
        }
    }

    private suspend fun confirmRoomStillLive(roomId: Long): Boolean {
        repeat(3) { attempt ->
            if (runCatching { repository.refresh(roomId) == LiveStatus.LIVE }.getOrDefault(false)) return true
            if (attempt < 2) delay(1_000L)
        }
        return false
    }

    private fun stopRecording(roomId: Long, reason: StopReason) {
        stopRequested.add(roomId)
        pauseRequested.remove(roomId)
        retryCounts.remove(roomId)
        sourceAttempts.remove(roomId)
        val recording = active[roomId]
        if (recording != null) {
            serviceScope.launch { recording.recorder.stop(recording.handle, reason) }
        } else {
            stopIfIdle()
        }
    }

    private fun pauseRecording(roomId: Long) {
        pauseRequested.add(roomId)
        retryCounts.remove(roomId)
        sourceAttempts.remove(roomId)
        val recording = active[roomId]
        if (recording != null) {
            recording.timerJob?.cancel()
            serviceScope.launch { recording.recorder.stop(recording.handle, StopReason.PAUSE) }
        } else {
            serviceScope.launch {
                val room = repository.room(roomId) ?: return@launch
                repository.setRoomTaskStatus(roomId, room.liveStatus, TaskStatus.PAUSED)
                pauseRequested.remove(roomId)
                stopIfIdle()
            }
        }
    }

    private fun recoverInterruptedTasks() {
        serviceScope.launch {
            val sessions = repository.activeSessions()
            sessions.forEach { session ->
                val preservedMedia = session.temporaryPath?.let { path ->
                    java.io.File(path).let { file ->
                        file.exists() && file.length() > 1024L
                    }
                } == true || session.outputUri != null || session.outputUris.isNotEmpty()
                val recoveryMessage = if (preservedMedia) {
                    "应用进程中断，已保留已录内容，可直接预览"
                } else {
                    "应用进程中断，未发现可恢复的媒体文件"
                }
                repository.updateSession(
                    session.copy(
                        status = TaskStatus.FAILED,
                        endedAt = System.currentTimeMillis(),
                        errorMessage = recoveryMessage,
                    ),
                )
                repository.log(
                    session.roomId,
                    session.id,
                    if (preservedMedia) "WARN" else "ERROR",
                    "INTERRUPTED_RECORDING_RECOVERED",
                    recoveryMessage,
                )
                val remainingStopAt = session.stopAtMillis?.takeIf { it > System.currentTimeMillis() }
                startRecording(session.roomId, remainingStopAt)
            }
            if (sessions.isEmpty()) stopIfIdle()
        }
    }

    private fun finishActive(roomId: Long) {
        active.remove(roomId)
        if (active.isEmpty()) {
            wakeLockRefreshJob?.cancel()
            wakeLockRefreshJob = null
            if (wakeLock.isHeld) wakeLock.release()
        }
        stopIfIdle()
    }

    private suspend fun roomTaskStatus(roomId: Long): TaskStatus =
        if (repository.room(roomId)?.enabled == true) TaskStatus.WAITING else TaskStatus.DISABLED

    private fun stopIfIdle() {
        if (monitoringActive) {
            val message = if (active.isEmpty()) "正在检测直播状态" else "${active.size} 个录制任务正在运行"
            getSystemService(android.app.NotificationManager::class.java).notify(
                RecordingNotifications.SERVICE_NOTIFICATION_ID,
                notifications.serviceNotification(message),
            )
            return
        }
        if (active.isEmpty()) {
            wakeLockRefreshJob?.cancel()
            wakeLockRefreshJob = null
            if (wakeLock.isHeld) wakeLock.release()
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

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        if (wakeLockRefreshJob == null) {
            wakeLockRefreshJob = serviceScope.launch {
                while (active.isNotEmpty()) {
                    delay(WAKE_LOCK_REFRESH_MILLIS)
                    if (active.isEmpty()) break
                    if (wakeLock.isHeld) wakeLock.release()
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
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
        RecorderState.COMPLETED, RecorderState.STOPPED -> TaskStatus.FINALIZING
        RecorderState.FAILED -> TaskStatus.FAILED
    }

    private fun retryDelayMillis(retryCount: Int): Long =
        (INITIAL_RETRY_DELAY_MILLIS * (1L shl retryCount.coerceIn(0, 4)))
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)

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
        private const val ACTION_START = "com.streamcap.mobile.action.START_RECORDING"
        private const val ACTION_STOP = "com.streamcap.mobile.action.STOP_RECORDING"
        private const val ACTION_PAUSE = "com.streamcap.mobile.action.PAUSE_RECORDING"
        private const val ACTION_RECOVER = "com.streamcap.mobile.action.RECOVER"
        private const val ACTION_MONITOR = "com.streamcap.mobile.action.MONITOR"
        private const val EXTRA_ROOM_ID = "room_id"
        private const val EXTRA_STOP_AT = "stop_at"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        private const val WAKE_LOCK_REFRESH_MILLIS = 8 * 60 * 1_000L
        private const val INITIAL_RETRY_DELAY_MILLIS = 2_000L
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private const val MAX_START_RETRIES = 4
        private const val MONITOR_POLL_MILLIS = 15_000L
        private const val MIN_MONITOR_INTERVAL_SECONDS = 15
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

        fun pauseIntent(context: Context, roomId: Long): Intent =
            Intent(context, RecordingService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_ROOM_ID, roomId)
            }

        fun recover(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_RECOVER),
            )
        }

        fun monitor(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java).setAction(ACTION_MONITOR),
            )
        }
    }
}
