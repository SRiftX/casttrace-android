package com.streamcap.mobile.data.repository

import com.streamcap.mobile.data.database.LiveRoomDao
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.data.database.RecordingSessionDao
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.data.database.TaskLogDao
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.data.database.toDomain
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.MAX_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MAX_MONITOR_INTERVAL_SECONDS
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_MONITOR_INTERVAL_SECONDS
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.platform.resolver.ShareLinkDiscovery
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class StreamCapRepository(
    private val roomDao: LiveRoomDao,
    private val sessionDao: RecordingSessionDao,
    private val logDao: TaskLogDao,
    private val resolvers: ResolverRegistry,
    private val shareLinks: ShareLinkDiscovery,
    private val credentialStore: CredentialStore,
) {
    val rooms: Flow<List<LiveRoom>> = roomDao.observeAll().map { rooms -> rooms.map { it.toDomain() } }
    val recordings: Flow<List<RecordingSession>> =
        sessionDao.observeAll().map { sessions -> sessions.map { it.toDomain() } }
    val logs: Flow<List<TaskLogEntity>> = logDao.observeRecent()
    val recordingEvents: Flow<List<TaskLogEntity>> = logDao.observeRecordingEvents()

    fun observeLatestErrorForRoom(roomId: Long): Flow<TaskLogEntity?> =
        logDao.observeLatestErrorForRoom(roomId)

    fun observeRecording(id: Long): Flow<RecordingSession?> =
        sessionDao.observeById(id).map { it?.toDomain() }

    fun observeRoom(id: Long): Flow<LiveRoom?> = roomDao.observeById(id).map { it?.toDomain() }

    suspend fun room(id: Long): LiveRoom? = roomDao.getById(id)?.toDomain()

    suspend fun monitoredRooms(): List<LiveRoom> = roomDao.getMonitoredRooms().map { it.toDomain() }

    suspend fun monitoringDue(room: LiveRoom, now: Long = System.currentTimeMillis()): Boolean {
        if (room.oneTimeEndAt != null && now >= room.oneTimeEndAt) {
            roomDao.expireOneTimeWindow(room.id, now)
            log(room.id, null, "INFO", "ONE_TIME_MONITOR_ENDED", "单次监控时段已结束")
            return false
        }
        return room.oneTimeStartAt == null || now >= room.oneTimeStartAt
    }

    suspend fun setOneTimeWindow(roomId: Long, startAt: Long?, endAt: Long?) {
        require((startAt == null && endAt == null) || (startAt != null && endAt != null && endAt > startAt)) { "结束时间必须晚于开始时间" }
        roomDao.setOneTimeWindow(roomId, startAt, endAt, System.currentTimeMillis())
    }

    suspend fun resumePausedMonitoredRooms(): Int = roomDao.resumePausedMonitoredRooms(System.currentTimeMillis())

    suspend fun finishInterruptedStatus(roomId: Long, status: TaskStatus) =
        roomDao.finishInterruptedStatus(roomId, status, System.currentTimeMillis())

    suspend fun addRoom(
        url: String,
        displayName: String? = null,
        options: RoomRecordingOptions = RoomRecordingOptions(),
    ): Long {
        val (normalizedInput, info) = resolveNewRoom(url)
        if (roomDao.getByNormalizedUrl(info.normalizedUrl) != null) {
            throw IllegalArgumentException("这个直播间已经在列表中")
        }
        return roomDao.insert(
            LiveRoomEntity(
                originalUrl = normalizedInput,
                normalizedUrl = info.normalizedUrl,
                platform = info.platform,
                anchorName = displayName?.trim().takeUnless { it.isNullOrBlank() } ?: info.anchorName,
                roomTitle = info.roomTitle,
                quality = options.quality,
                mediaType = options.mediaType,
                outputFormat = options.outputFormat,
                flvDirectDownload = options.flvDirectDownload,
                storageDirectoryUri = options.storageDirectoryUri,
                segmentEnabled = options.segmentEnabled,
                segmentSeconds = options.segmentSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS),
                scheduledMonitoringEnabled = options.scheduledMonitoringEnabled,
                autoRecord = options.scheduledMonitoringEnabled,
                notificationsEnabled = options.notificationsEnabled,
                checkIntervalSeconds = options.checkIntervalSeconds.coerceIn(
                    MIN_MONITOR_INTERVAL_SECONDS,
                    MAX_MONITOR_INTERVAL_SECONDS,
                ),
            ),
        )
    }

    suspend fun setEnabled(roomId: Long, enabled: Boolean) {
        roomDao.setEnabled(
            id = roomId,
            enabled = enabled,
            status = if (enabled) TaskStatus.WAITING else TaskStatus.DISABLED,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun setQuality(roomId: Long, quality: String) {
        roomDao.setQuality(roomId, quality, System.currentTimeMillis())
    }

    suspend fun setScheduledMonitoringEnabled(roomId: Long, enabled: Boolean) {
        roomDao.setScheduledMonitoringEnabled(roomId, enabled, System.currentTimeMillis())
    }

    suspend fun updateRecordingOptions(roomId: Long, options: RoomRecordingOptions) {
        roomDao.updateRecordingOptions(
            id = roomId,
            mediaType = options.mediaType,
            outputFormat = options.outputFormat,
            quality = options.quality,
            flvDirectDownload = options.flvDirectDownload,
            storageDirectoryUri = options.storageDirectoryUri,
            segmentEnabled = options.segmentEnabled,
            segmentSeconds = options.segmentSeconds.coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS),
            scheduledMonitoringEnabled = options.scheduledMonitoringEnabled,
            notificationsEnabled = options.notificationsEnabled,
            checkIntervalSeconds = options.checkIntervalSeconds.coerceIn(
                MIN_MONITOR_INTERVAL_SECONDS,
                MAX_MONITOR_INTERVAL_SECONDS,
            ),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteRoom(roomId: Long) {
        val room = roomDao.getById(roomId) ?: return
        roomDao.delete(room)
    }

    suspend fun previewRoom(url: String): Pair<LiveRoomInfo, Boolean> {
        val (_, info) = resolveNewRoom(url)
        return info to (roomDao.getByNormalizedUrl(info.normalizedUrl) != null)
    }

    private suspend fun resolveNewRoom(url: String): Pair<String, LiveRoomInfo> {
        val input = shareLinks.resolve(url)
        suspend fun resolve(address: String): LiveRoomInfo {
            val resolver = resolvers.resolverFor(address)
            return resolver.resolveRoom(address, credentialStore.get(resolver.platformId))
        }
        val first = runCatching { resolve(input) }
        first.getOrNull()?.let { return input to it }
        // A platform share page may have the right domain but no room ID in its path.
        val expanded = runCatching { shareLinks.resolve(url, inspectSupported = true) }.getOrNull()
        if (expanded != null && expanded != input) {
            return expanded to resolve(expanded)
        }
        throw requireNotNull(first.exceptionOrNull())
    }

    suspend fun activeSessionForRoom(roomId: Long): RecordingSessionEntity? =
        sessionDao.getActiveForRoom(roomId)

    /** A killed process cannot finish the normal CHECKING -> WAITING/ERROR transition. */
    suspend fun resetStaleChecks(now: Long = System.currentTimeMillis()): Int =
        roomDao.resetStaleChecks(now - CHECK_STALE_MILLIS, now)

    suspend fun refresh(roomId: Long): LiveStatus {
        val room = room(roomId) ?: return LiveStatus.ERROR
        val now = System.currentTimeMillis()
        val paused = room.taskStatus == TaskStatus.PAUSED
        roomDao.setCheckStatus(
            roomId,
            LiveStatus.CHECKING,
            if (paused) TaskStatus.PAUSED else TaskStatus.CHECKING,
            now,
        )
        val resolver = resolvers.resolverForPlatform(room.platform)
        val checked = try {
            withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
                resolver.checkRoom(room, credentialStore.get(room.platform))
            } ?: run {
                log(roomId, null, "ERROR", "LIVE_CHECK_TIMEOUT", "检查直播状态超时（${CHECK_TIMEOUT_MILLIS / 1_000} 秒）")
                com.streamcap.mobile.platform.resolver.RoomCheckResult(LiveStatus.ERROR)
            }
        } catch (error: CancellationException) {
            // Service shutdown/restart must not leave a persisted CHECKING marker.
            withContext(NonCancellable) {
                roomDao.setCheckStatus(
                    roomId,
                    room.liveStatus.takeIf { it != LiveStatus.CHECKING } ?: LiveStatus.UNKNOWN,
                    if (paused) TaskStatus.PAUSED else TaskStatus.WAITING,
                    System.currentTimeMillis(),
                )
            }
            throw error
        } catch (error: Throwable) {
            log(roomId, null, "ERROR", "LIVE_CHECK_FAILED", error.message ?: "检查开播状态失败")
            com.streamcap.mobile.platform.resolver.RoomCheckResult(LiveStatus.ERROR)
        }
        val result = checked.status
        checked.info?.let { info ->
            val resolvedName = if (room.anchorName.startsWith("抖音主播 ") || room.anchorName.isBlank()) info.anchorName else room.anchorName
            roomDao.updateResolvedInfo(roomId, room.anchorName, resolvedName, info.roomTitle)
        }
        roomDao.setCheckStatus(
            roomId,
            result,
            when {
                paused -> TaskStatus.PAUSED
                result == LiveStatus.ERROR -> TaskStatus.FAILED
                else -> TaskStatus.WAITING
            },
            System.currentTimeMillis(),
        )
        log(roomId, null, if (result == LiveStatus.ERROR) "ERROR" else "INFO", "LIVE_CHECK", "检查结果：$result")
        return result
    }

    suspend fun streams(roomId: Long): List<StreamSource> {
        val room = requireNotNull(room(roomId)) { "直播间不存在" }
        return withTimeoutOrNull(45_000L) {
            resolvers.resolverForPlatform(room.platform).getStreams(room, credentialStore.get(room.platform))
        } ?: error("解析直播源超时，请稍后重试")
    }

    // Recording checks must not replace RECORDING/FINALIZING with monitor task states.
    suspend fun recordingLiveStatus(roomId: Long): LiveStatus {
        val room = room(roomId) ?: return LiveStatus.UNKNOWN
        return try {
            withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
                resolvers.resolverForPlatform(room.platform).checkLive(room, credentialStore.get(room.platform))
            } ?: LiveStatus.UNKNOWN
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LiveStatus.UNKNOWN
        }
    }

    companion object {
        private const val CHECK_TIMEOUT_MILLIS = 15_000L
        private const val CHECK_STALE_MILLIS = 45_000L
    }

    suspend fun createSession(
        room: LiveRoom,
        source: StreamSource,
        temporaryPath: String,
        stopAtMillis: Long?,
        outputFormat: String,
    ): Long =
        requireNotNull(sessionDao.insertIfNoActive(
            RecordingSessionEntity(
                roomId = room.id,
                    roomName = room.anchorName,
                    roomTitle = room.roomTitle,
                status = TaskStatus.STARTING,
                startedAt = System.currentTimeMillis(),
                temporaryPath = temporaryPath,
                containerFormat = outputFormat.lowercase(),
                streamType = source.type,
                stopAtMillis = stopAtMillis,
            ),
        )) { "该直播间已有录制任务" }

    suspend fun createStartFailureSession(room: LiveRoom, message: String): Long =
        sessionDao.insert(
            RecordingSessionEntity(
                roomId = room.id,
                roomName = room.anchorName,
                roomTitle = room.roomTitle,
                status = TaskStatus.FAILED,
                startedAt = System.currentTimeMillis(),
                endedAt = System.currentTimeMillis(),
                containerFormat = room.outputFormat.lowercase(),
                streamType = StreamType.UNKNOWN,
                errorMessage = message,
            ),
        )

    suspend fun session(id: Long): RecordingSessionEntity? = sessionDao.getById(id)

    suspend fun updateSession(session: RecordingSessionEntity) = sessionDao.update(session)

    suspend fun deleteSession(session: RecordingSessionEntity) = sessionDao.delete(session)

    suspend fun activeSessions(): List<RecordingSessionEntity> = sessionDao.getActive()
    suspend fun failedPublications(): List<RecordingSessionEntity> = sessionDao.getFailedPublications()

    suspend fun setRoomTaskStatus(roomId: Long, liveStatus: LiveStatus, taskStatus: TaskStatus) {
        roomDao.setStatus(roomId, liveStatus, taskStatus, System.currentTimeMillis())
    }

    suspend fun log(
        roomId: Long?,
        sessionId: Long?,
        level: String,
        eventType: String,
        message: String,
    ) {
        logDao.insert(
            TaskLogEntity(
                roomId = roomId,
                sessionId = sessionId,
                level = level,
                eventType = eventType,
                message = com.streamcap.mobile.diagnostics.TaskLogExporter.sanitize(message),
            ),
        )
    }
}
