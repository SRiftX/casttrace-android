package com.streamcap.mobile.service

import android.content.Context
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuController
import com.streamcap.mobile.danmaku.DanmakuEventStore
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.network.NetworkPolicyException
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.notification.RecordingNotifications
import com.streamcap.mobile.recording.RecordingSegmentPolicy
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecordingRequest
import com.streamcap.mobile.recording.engine.StopReason
import com.streamcap.mobile.recording.ffmpeg.FfmpegHlsRecorder
import com.streamcap.mobile.recording.flv.DirectHttpRecorder
import com.streamcap.mobile.recording.hls.HlsRecorder
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import com.streamcap.mobile.storage.RecordingStorage
import dagger.hilt.android.scopes.ServiceScoped
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient

internal interface RecordingStartupHost {
    fun hasRetryPending(roomId: Long): Boolean
    fun acquireWakeLock()
    fun enforceNetworkPolicy()
    fun pauseRecording(
        roomId: Long,
        reason: StopReason = StopReason.PAUSE,
        disableAutomaticRecording: Boolean = false,
    )
    suspend fun scheduleRetry(roomId: Long, stopAtMillis: Long?, retryCount: Int)
    suspend fun roomTaskStatus(roomId: Long): TaskStatus
    suspend fun recordingStarted(recording: ActiveRecording)
    fun stopIfIdle()
}

/** Creates a durable session before starting its recorder and owns startup failure policy. */
@ServiceScoped
class RecordingStartupCoordinator @Inject constructor(
    private val preferences: AppPreferences,
    private val networkPolicy: RecordingNetworkPolicy,
    private val httpClient: OkHttpClient,
    private val credentialStore: CredentialStore,
    private val danmakuClients: RealDanmakuClientFactory,
    private val recordingStorage: RecordingStorage,
    private val repository: StreamCapRepository,
    private val runtimeState: RecordingRuntimeState,
    private val lineSelector: RecordingLineSelector,
) {
    private lateinit var serviceScope: CoroutineScope
    private lateinit var directRecorder: DirectHttpRecorder
    private lateinit var hlsRecorder: HlsRecorder
    private lateinit var ffmpegHlsRecorder: FfmpegHlsRecorder

    internal fun initialize(scope: CoroutineScope) {
        serviceScope = scope
        directRecorder = DirectHttpRecorder(httpClient, scope)
        hlsRecorder = HlsRecorder(httpClient, scope)
        ffmpegHlsRecorder = FfmpegHlsRecorder(scope, httpClient)
    }

    internal fun start(
        context: Context,
        notifications: RecordingNotifications,
        roomId: Long,
        stopAtMillis: Long?,
        host: RecordingStartupHost,
    ) {
        if (runtimeState.stopRequested.contains(roomId) || runtimeState.pauseRequested.contains(roomId)) return
        if (host.hasRetryPending(roomId)) return
        if (stopAtMillis != null && stopAtMillis <= System.currentTimeMillis()) return
        if (runtimeState.active.containsKey(roomId) || !runtimeState.starting.add(roomId)) return
        serviceScope.launch {
            val room = repository.room(roomId) ?: run {
                runtimeState.starting.remove(roomId)
                return@launch
            }
            if (!room.enabled || (room.taskStatus == TaskStatus.PAUSED && !runtimeState.resumeRequested.remove(roomId)) ||
                runtimeState.pauseRequested.contains(roomId)
            ) {
                runtimeState.starting.remove(roomId)
                return@launch
            }
            if (stopAtMillis == null && room.oneTimeEndAt != null && !repository.monitoringDue(room)) {
                runtimeState.starting.remove(roomId)
                return@launch
            }
            if (repository.activeSessionForRoom(roomId) != null) {
                runtimeState.starting.remove(roomId)
                return@launch
            }
            var sessionCreated = false
            var createdSessionId: Long? = null
            var pendingDanmaku: DanmakuController? = null
            try {
                host.acquireWakeLock()
                networkPolicy.requireAllowed()
                repository.setRoomTaskStatus(roomId, room.liveStatus, TaskStatus.STARTING)
                val settings = preferences.settings.first()
                val storageRoot = recordingStorage.recordingRoot()
                val freeBytes = storageRoot.usableSpace
                check(freeBytes >= MIN_FREE_BYTES) {
                    "可用存储空间不足（至少需要 ${MIN_FREE_BYTES / 1_073_741_824} GB），已取消录制"
                }
                val availableSources = lineSelector.availableSources(roomId)
                val selection = lineSelector.select(room, availableSources, runtimeState.sourceAttempts[roomId] ?: 0)
                val source = selection.source
                val actualFormat = selection.outputFormat
                val recorderExtension = if (source.type == StreamType.FLV) "flv" else "ts"
                val temporary = recordingStorage.createTemporaryFile(room.anchorName, recorderExtension)
                if (runtimeState.stopRequested.contains(roomId) || runtimeState.pauseRequested.contains(roomId) ||
                    repository.room(roomId)?.enabled != true
                ) {
                    temporary.delete()
                    runtimeState.starting.remove(roomId)
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
                val danmakuController = if (danmakuClients.supports(room.platform)) {
                    val eventFile = File(context.filesDir, "danmaku/session-$sessionId/events.jsonl")
                    val store = DanmakuEventStore(eventFile)
                    repository.session(sessionId)?.let { entity ->
                        repository.updateSession(entity.copy(danmakuPath = eventFile.absolutePath))
                    }
                    DanmakuController(
                        scope = serviceScope,
                        client = danmakuClients.forPlatform(room.platform),
                        store = store,
                    ) { state ->
                        serviceScope.launch {
                            repository.log(
                                roomId,
                                sessionId,
                                if (state.startsWith("error")) "WARN" else "INFO",
                                "DANMAKU_$state",
                                "弹幕连接状态：$state",
                            )
                        }
                    }
                } else null
                pendingDanmaku = danmakuController
                repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.STARTING)
                repository.log(roomId, sessionId, "INFO", "RECORDING_START", "开始录制 ${source.type}")

                val selectedRecorder: Recorder = if (source.type == StreamType.HLS) {
                    if (source.separateAudio) ffmpegHlsRecorder else hlsRecorder
                } else {
                    directRecorder
                }
                var lastFlvSource = source
                var lastHlsHost = java.net.URI(source.url).host
                networkPolicy.requireAllowed()
                check(!runtimeState.pauseRequested.contains(roomId) && !runtimeState.stopRequested.contains(roomId)) {
                    "录制启动已取消"
                }
                val mediaStart = android.os.SystemClock.elapsedRealtime()
                val handle = selectedRecorder.start(
                    RecordingRequest(
                        url = source.url,
                        headers = source.headers,
                        streamType = source.type,
                        destination = temporary,
                        segmentSeconds = RecordingSegmentPolicy.effectiveSeconds(room.segmentEnabled, room.segmentSeconds),
                        eventLogger = { type, fields ->
                            serviceScope.launch {
                                val details = (mapOf(
                                    "time" to System.currentTimeMillis().toString(),
                                    "sessionId" to sessionId.toString(),
                                ) + fields).entries.joinToString(" ") { (key, value) -> "$key=$value" }
                                android.util.Log.i("StreamCapEvent", "$type $details")
                                repository.log(roomId, sessionId, "INFO", type, details)
                            }
                        },
                        refreshSource = when (source.type) {
                            StreamType.HLS -> {
                                val refresh: suspend (Boolean) -> com.streamcap.mobile.domain.model.StreamSource? = { _ ->
                                    val refreshedSources = lineSelector.availableSources(roomId)
                                    repository.log(
                                        roomId,
                                        sessionId,
                                        "INFO",
                                        "STREAM_REFRESH_REQUESTED",
                                        "重新获取 HLS 线路；这是取流重试时间，不是精确缺失区间",
                                    )
                                    val hlsSources = refreshedSources.filter { it.type == StreamType.HLS }.distinctBy { it.url }
                                    HlsSourceSelector(httpClient).select(hlsSources, room.quality, lastHlsHost)
                                        .also { lastHlsHost = java.net.URI(it.url).host }
                                }
                                refresh
                            }
                            StreamType.FLV -> {
                                val refresh: suspend (Boolean) -> com.streamcap.mobile.domain.model.StreamSource? = { receivedMedia ->
                                    val refreshedSources = lineSelector.availableSources(roomId)
                                        .filter { it.type == StreamType.FLV }
                                        .distinctBy { it.url }
                                    repository.log(
                                        roomId,
                                        sessionId,
                                        "INFO",
                                        "STREAM_REFRESH_REQUESTED",
                                        "重新获取 FLV 线路；这是取流重试时间，不是精确缺失区间",
                                    )
                                    com.streamcap.mobile.recording.flv.FlvSourceSelector.select(
                                        refreshedSources,
                                        lastFlvSource,
                                        receivedMedia,
                                    )?.also { lastFlvSource = it }
                                }
                                refresh
                            }
                            else -> null
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
                danmakuController?.start(
                    DanmakuConnectContext(
                        sessionId = sessionId,
                        platform = room.platform,
                        roomUrl = room.normalizedUrl,
                        cookie = credentialStore.get(room.platform)?.cookie,
                        mediaStartedElapsedMs = mediaStart,
                    ),
                )
                runtimeState.active[roomId] = recording
                pendingDanmaku = null
                host.enforceNetworkPolicy()
                runtimeState.starting.remove(roomId)
                host.acquireWakeLock()
                host.recordingStarted(recording)
            } catch (error: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { pendingDanmaku?.stop("start-failed") }
                }
                if (error is kotlinx.coroutines.CancellationException) {
                    runtimeState.starting.remove(roomId)
                    throw error
                }
                if (error is com.streamcap.mobile.recording.engine.LiveStreamEndedException) {
                    repository.setRoomTaskStatus(roomId, LiveStatus.OFFLINE, host.roomTaskStatus(roomId))
                    repository.log(roomId, createdSessionId, "INFO", "LIVE_ENDED", "主播已下播，停止获取直播源")
                    runtimeState.starting.remove(roomId)
                    runtimeState.retryCounts.remove(roomId)
                    runtimeState.sourceAttempts.remove(roomId)
                    host.stopIfIdle()
                    return@launch
                }
                if (error is NetworkPolicyException || runtimeState.pauseRequested.contains(roomId)) {
                    createdSessionId?.let { id ->
                        repository.session(id)?.let { session ->
                            repository.updateSession(
                                session.copy(status = TaskStatus.PAUSED, endedAt = System.currentTimeMillis(), errorMessage = error.message),
                            )
                        }
                    }
                    runtimeState.starting.remove(roomId)
                    host.pauseRecording(roomId)
                    repository.log(roomId, null, "WARN", "NETWORK_POLICY_PAUSED", error.message ?: "录制已暂停")
                    return@launch
                }
                val errorMessage = error.message ?: "启动录制失败"
                val retryCount = runtimeState.retryCounts[roomId] ?: 0
                if (!sessionCreated && retryCount == 0) repository.createStartFailureSession(room, errorMessage)
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
                val shouldRetry = RecordingRetryPolicy.shouldRetryStartup(
                    stopRequested = runtimeState.stopRequested.contains(roomId),
                    pauseRequested = runtimeState.pauseRequested.contains(roomId),
                    stopAtMillis = stopAtMillis,
                    nowMillis = System.currentTimeMillis(),
                    retryCount = retryCount,
                    scheduledMonitoringEnabled = room.scheduledMonitoringEnabled,
                    roomEnabled = repository.room(roomId)?.enabled == true,
                )
                if (shouldRetry) {
                    runtimeState.retryCounts[roomId] = retryCount + 1
                    runtimeState.sourceAttempts[roomId] = (runtimeState.sourceAttempts[roomId] ?: 0) + 1
                    repository.setRoomTaskStatus(roomId, LiveStatus.LIVE, TaskStatus.RECONNECTING)
                    repository.log(
                        roomId,
                        null,
                        "INFO",
                        "RECORDING_RETRY",
                        "录制启动异常，将在刷新直播地址后进行第 ${retryCount + 1} 次重试",
                    )
                    host.scheduleRetry(roomId, stopAtMillis, retryCount)
                    runtimeState.starting.remove(roomId)
                } else {
                    runtimeState.starting.remove(roomId)
                    runtimeState.retryCounts.remove(roomId)
                    runtimeState.sourceAttempts.remove(roomId)
                    host.stopIfIdle()
                }
            }
        }
    }

    private companion object {
        const val MIN_FREE_BYTES = 512L * 1_048_576L
    }
}
