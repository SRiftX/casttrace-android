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
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class StreamCapRepository(
    private val roomDao: LiveRoomDao,
    private val sessionDao: RecordingSessionDao,
    private val logDao: TaskLogDao,
    private val resolvers: ResolverRegistry,
) {
    val rooms: Flow<List<LiveRoom>> = roomDao.observeAll().map { rooms -> rooms.map { it.toDomain() } }
    val recordings: Flow<List<RecordingSession>> =
        sessionDao.observeAll().map { sessions -> sessions.map { it.toDomain() } }
    val logs: Flow<List<TaskLogEntity>> = logDao.observeRecent()

    fun observeRecording(id: Long): Flow<RecordingSession?> =
        sessionDao.observeById(id).map { it?.toDomain() }

    fun observeRoom(id: Long): Flow<LiveRoom?> = roomDao.observeById(id).map { it?.toDomain() }

    suspend fun room(id: Long): LiveRoom? = roomDao.getById(id)?.toDomain()

    suspend fun monitoredRooms(): List<LiveRoom> = roomDao.getMonitoredRooms().map { it.toDomain() }

    suspend fun addRoom(
        url: String,
        displayName: String? = null,
        options: RoomRecordingOptions = RoomRecordingOptions(),
    ): Long {
        val normalizedInput = url.trim()
        val resolver = resolvers.resolverFor(normalizedInput)
        val info = resolver.resolveRoom(normalizedInput)
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
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteRoom(roomId: Long) {
        val room = roomDao.getById(roomId) ?: return
        roomDao.delete(room)
    }

    suspend fun refresh(roomId: Long): LiveStatus {
        val room = room(roomId) ?: return LiveStatus.ERROR
        val now = System.currentTimeMillis()
        val paused = room.taskStatus == TaskStatus.PAUSED
        roomDao.setStatus(
            roomId,
            LiveStatus.CHECKING,
            if (paused) TaskStatus.PAUSED else TaskStatus.CHECKING,
            now,
        )
        val resolver = resolvers.resolverForPlatform(room.platform)
        val checked = runCatching { resolver.checkRoom(room) }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            log(roomId, null, "ERROR", "LIVE_CHECK_FAILED", error.message ?: "检查开播状态失败")
            com.streamcap.mobile.platform.resolver.RoomCheckResult(LiveStatus.ERROR)
        }
        val result = checked.status
        checked.info?.let { info ->
            val resolvedName = if (room.anchorName.startsWith("抖音主播 ") || room.anchorName.isBlank()) info.anchorName else room.anchorName
            roomDao.updateResolvedInfo(roomId, room.anchorName, resolvedName, info.roomTitle)
        }
        roomDao.setStatus(
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
        return resolvers.resolverForPlatform(room.platform).getStreams(room)
    }

    suspend fun createSession(
        room: LiveRoom,
        source: StreamSource,
        temporaryPath: String,
        stopAtMillis: Long?,
        outputFormat: String,
    ): Long =
        sessionDao.insert(
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
        )

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
                message = message,
            ),
        )
    }
}
