package com.streamcap.mobile.data.repository

import com.streamcap.mobile.data.database.LiveRoomDao
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.data.database.LiveReminderDao
import com.streamcap.mobile.data.database.LiveReminderEntity
import com.streamcap.mobile.data.database.RecordingSessionDao
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.data.database.TaskLogDao
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.data.database.toDomain
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.domain.model.DEFAULT_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MAX_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_MONITOR_INTERVAL_SECONDS
import com.streamcap.mobile.domain.model.MAX_MONITOR_INTERVAL_SECONDS
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.platform.resolver.ShareLinkDiscovery
import com.streamcap.mobile.platform.resolver.RoomCheckResult
import com.streamcap.mobile.platform.resolver.UnsupportedPlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Owns task-log queries and persistence, including message sanitization. */
class TaskLogRepository(private val logDao: TaskLogDao) {
    val logs: Flow<List<TaskLogEntity>> = logDao.observeRecent(limit = 500)
    val logCount: Flow<Int> = logDao.observeCount()
    val recordingEvents: Flow<List<TaskLogEntity>> = logDao.observeRecordingEvents()

    fun observeLogs(limit: Int, level: String?, roomId: Long?, keyOnly: Boolean): Flow<List<TaskLogEntity>> =
        logDao.observeRecent(limit, level, roomId, keyOnly)

    fun observeLogCount(level: String?, roomId: Long?, keyOnly: Boolean): Flow<Int> =
        logDao.observeCount(level, roomId, keyOnly)

    fun observeLatestErrorForRoom(roomId: Long): Flow<TaskLogEntity?> =
        logDao.observeLatestErrorForRoom(roomId)

    suspend fun logsForExport(): List<TaskLogEntity> = logDao.recentForExport()

    suspend fun log(roomId: Long?, sessionId: Long?, level: String, eventType: String, message: String) {
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

/** Owns recording-session persistence and the room-level active-session guard. */
class RecordingRepository(
    private val roomDao: LiveRoomDao,
    private val sessionDao: RecordingSessionDao,
) {
    val recordings: Flow<List<RecordingSession>> =
        sessionDao.observeAll().map { sessions -> sessions.map { it.toDomain() } }

    fun observeRecording(id: Long): Flow<RecordingSession?> =
        sessionDao.observeById(id).map { it?.toDomain() }

    suspend fun activeSessionForRoom(roomId: Long): RecordingSessionEntity? =
        sessionDao.getActiveForRoom(roomId)

    suspend fun createSession(
        room: LiveRoom,
        source: StreamSource,
        temporaryPath: String,
        stopAtMillis: Long?,
        outputFormat: String,
    ): Long = requireNotNull(
        sessionDao.insertIfNoActive(
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
        ),
    ) { "该直播间已有录制任务" }

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
}

/** Resolves user-entered URLs and platform share pages through one injected resolver graph. */
class RoomUrlResolver(
    private val shareLinks: ShareLinkDiscovery,
    private val resolvers: ResolverRegistry,
    private val credentialStore: CredentialStore,
) {
    suspend fun resolve(url: String): Pair<String, LiveRoomInfo> {
        val input = shareLinks.resolve(url)
        suspend fun resolveAddress(address: String): LiveRoomInfo {
            val resolver = resolvers.resolverFor(address)
            return resolver.resolveRoom(address, credentialStore.get(resolver.platformId))
        }
        val first = runCatching { resolveAddress(input) }
        first.getOrNull()?.let { return input to it }
        val expanded = runCatching { shareLinks.resolve(url, inspectSupported = true) }.getOrNull()
        if (expanded != null && expanded != input) return expanded to resolveAddress(expanded)
        throw requireNotNull(first.exceptionOrNull())
    }
}

/** Room metadata and configuration operations. */
class RoomRepository(
    private val roomDao: LiveRoomDao,
    private val urlResolver: RoomUrlResolver,
    private val taskLogs: TaskLogRepository,
) {
    val rooms: Flow<List<LiveRoom>> = roomDao.observeAll().map { rooms -> rooms.map { it.toDomain() } }

    fun observeRoom(id: Long): Flow<LiveRoom?> = roomDao.observeById(id).map { it?.toDomain() }
    suspend fun room(id: Long): LiveRoom? = roomDao.getById(id)?.toDomain()
    suspend fun monitoredRooms(): List<LiveRoom> = roomDao.getMonitoredRooms().map { it.toDomain() }

    suspend fun monitoringDue(room: LiveRoom, now: Long = System.currentTimeMillis()): Boolean {
        if (room.oneTimeEndAt != null && now >= room.oneTimeEndAt) {
            roomDao.expireOneTimeWindow(room.id, now)
            taskLogs.log(room.id, null, "INFO", "ONE_TIME_MONITOR_ENDED", "单次监控时段已结束")
            return false
        }
        return room.oneTimeStartAt == null || now >= room.oneTimeStartAt
    }

    suspend fun setOneTimeWindow(roomId: Long, startAt: Long?, endAt: Long?) {
        require((startAt == null && endAt == null) || (startAt != null && endAt != null && endAt > startAt)) {
            "结束时间必须晚于开始时间"
        }
        roomDao.setOneTimeWindow(roomId, startAt, endAt, System.currentTimeMillis())
    }

    suspend fun repairPausedRoomSettings(): Int = roomDao.repairPausedRoomSettings(System.currentTimeMillis())

    suspend fun finishInterruptedStatus(roomId: Long, status: TaskStatus) =
        roomDao.finishInterruptedStatus(roomId, status, System.currentTimeMillis())

    suspend fun addRoom(
        url: String,
        displayName: String? = null,
        options: RoomRecordingOptions = RoomRecordingOptions(),
    ): Long {
        val (normalizedInput, info) = urlResolver.resolve(url)
        if (roomDao.getByNormalizedUrl(info.normalizedUrl) != null) throw IllegalArgumentException("这个直播间已经在列表中")
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

    suspend fun setQuality(roomId: Long, quality: String) =
        roomDao.setQuality(roomId, quality, System.currentTimeMillis())

    suspend fun setScheduledMonitoringEnabled(roomId: Long, enabled: Boolean) =
        roomDao.setScheduledMonitoringEnabled(roomId, enabled, System.currentTimeMillis())

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
            notificationsEnabled = options.notificationsEnabled,
            checkIntervalSeconds = options.checkIntervalSeconds.coerceIn(
                MIN_MONITOR_INTERVAL_SECONDS,
                MAX_MONITOR_INTERVAL_SECONDS,
            ),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun deleteRoom(roomId: Long) {
        roomDao.getById(roomId)?.let { room -> roomDao.delete(room) }
    }

    suspend fun previewRoom(url: String): Pair<LiveRoomInfo, Boolean> {
        val (_, info) = urlResolver.resolve(url)
        return info to (roomDao.getByNormalizedUrl(info.normalizedUrl) != null)
    }
}

/** Reminder data and check-result persistence. */
class ReminderRepository(
    private val reminderDao: LiveReminderDao,
    private val roomDao: LiveRoomDao,
    private val urlResolver: RoomUrlResolver,
    private val resolvers: ResolverRegistry,
    private val credentialStore: CredentialStore,
) {
    val reminders: Flow<List<LiveReminder>> = reminderDao.observeAll().map { reminders -> reminders.map { it.toDomain() } }

    suspend fun enabledReminders(): List<LiveReminder> = reminderDao.getEnabled().map { it.toDomain() }
    suspend fun hasEnabledReminders(): Boolean = reminderDao.getEnabled().isNotEmpty()

    suspend fun addReminder(url: String): Boolean {
        val (normalizedInput, info) = urlResolver.resolve(url)
        return reminderDao.insert(
            LiveReminderEntity(
                originalUrl = normalizedInput,
                normalizedUrl = info.normalizedUrl,
                platform = info.platform,
                anchorName = info.anchorName,
                roomTitle = info.roomTitle,
            ),
        ) != -1L
    }

    suspend fun importRoomAsReminder(roomId: Long): Boolean {
        val room = roomDao.getById(roomId) ?: return false
        return reminderDao.insert(
            LiveReminderEntity(
                originalUrl = room.originalUrl,
                normalizedUrl = room.normalizedUrl,
                platform = room.platform,
                anchorName = room.anchorName,
                roomTitle = room.roomTitle,
            ),
        ) != -1L
    }

    suspend fun setReminderEnabled(reminderId: Long, enabled: Boolean) =
        reminderDao.setEnabled(reminderId, enabled, System.currentTimeMillis())

    suspend fun setStrongNotificationEnabled(reminderId: Long, enabled: Boolean) =
        reminderDao.setStrongNotificationEnabled(reminderId, enabled, System.currentTimeMillis())

    suspend fun deleteReminder(reminderId: Long) {
        reminderDao.getById(reminderId)?.let { reminderDao.delete(it) }
    }

    suspend fun checkReminder(reminderId: Long, notificationsAllowed: Boolean = true): Boolean {
        val reminder = reminderDao.getById(reminderId) ?: return false
        if (!reminder.enabled) return false
        val now = System.currentTimeMillis()
        reminderDao.setChecking(reminderId, now)
        val result = try {
            val resolver = resolvers.resolverForPlatform(reminder.platform)
            withTimeoutOrNull(LIVE_CHECK_TIMEOUT_MILLIS) {
                resolver.checkRoom(reminder.toResolverRoom(), credentialStore.get(reminder.platform)).status
            } ?: LiveStatus.ERROR
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                reminderDao.setCheckResult(
                    reminderId,
                    reminder.liveStatus,
                    reminder.notifiedForCurrentLive,
                    reminder.lastCheckAt ?: now,
                )
            }
            throw cancelled
        } catch (_: Exception) {
            LiveStatus.ERROR
        }
        return reminderDao.applyCheckResult(reminderId, result, System.currentTimeMillis(), notificationsAllowed)
    }

    private companion object {
        const val LIVE_CHECK_TIMEOUT_MILLIS = 15_000L
    }
}

/** Live checks, stream lookup, and stale CHECKING recovery. */
class LiveStatusChecker(
    private val roomDao: LiveRoomDao,
    private val resolvers: ResolverRegistry,
    private val credentialStore: CredentialStore,
    private val taskLogs: TaskLogRepository,
) {
    suspend fun resetStaleChecks(now: Long = System.currentTimeMillis()): Int =
        roomDao.resetStaleChecks(now - CHECK_STALE_MILLIS, now)

    suspend fun refresh(roomId: Long): LiveStatus {
        val room = roomDao.getById(roomId)?.toDomain() ?: return LiveStatus.ERROR
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
                taskLogs.log(roomId, null, "ERROR", "LIVE_CHECK_TIMEOUT", "检查直播状态超时（${CHECK_TIMEOUT_MILLIS / 1_000} 秒）")
                RoomCheckResult(LiveStatus.ERROR)
            }
        } catch (error: CancellationException) {
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
            taskLogs.log(roomId, null, "ERROR", "LIVE_CHECK_FAILED", error.message ?: "检查开播状态失败")
            RoomCheckResult(LiveStatus.ERROR)
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
        taskLogs.log(roomId, null, if (result == LiveStatus.ERROR) "ERROR" else "INFO", "LIVE_CHECK", "检查结果：$result")
        return result
    }

    suspend fun streams(roomId: Long): List<StreamSource> {
        val room = requireNotNull(roomDao.getById(roomId)?.toDomain()) { "直播间不存在" }
        return streams(room)
    }

    suspend fun streams(reminder: LiveReminder): List<StreamSource> = streams(reminder.toResolverRoom())

    private suspend fun streams(room: LiveRoom): List<StreamSource> = withTimeoutOrNull(STREAM_LOOKUP_TIMEOUT_MILLIS) {
        resolvers.resolverForPlatform(room.platform).getStreams(room, credentialStore.get(room.platform))
    } ?: error("解析直播源超时，请稍后重试")

    // Recording checks must not replace RECORDING/FINALIZING with monitor task states.
    suspend fun recordingLiveStatus(roomId: Long): LiveStatus {
        val room = roomDao.getById(roomId)?.toDomain() ?: return LiveStatus.UNKNOWN
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

    private companion object {
        const val CHECK_TIMEOUT_MILLIS = 15_000L
        const val CHECK_STALE_MILLIS = 45_000L
        const val STREAM_LOOKUP_TIMEOUT_MILLIS = 45_000L
    }
}

private fun LiveReminderEntity.toResolverRoom() = toResolverRoom(
    id = id,
    originalUrl = originalUrl,
    normalizedUrl = normalizedUrl,
    platform = platform,
    anchorName = anchorName,
    roomTitle = roomTitle,
    liveStatus = liveStatus,
    lastCheckAt = lastCheckAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun LiveReminder.toResolverRoom() = toResolverRoom(
    id = id,
    originalUrl = originalUrl,
    normalizedUrl = normalizedUrl,
    platform = platform,
    anchorName = anchorName,
    roomTitle = roomTitle,
    liveStatus = liveStatus,
    lastCheckAt = lastCheckAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun toResolverRoom(
    id: Long,
    originalUrl: String,
    normalizedUrl: String,
    platform: String,
    anchorName: String,
    roomTitle: String?,
    liveStatus: LiveStatus,
    lastCheckAt: Long?,
    createdAt: Long,
    updatedAt: Long,
) = LiveRoom(
    id = id,
    originalUrl = originalUrl,
    normalizedUrl = normalizedUrl,
    platform = platform,
    anchorName = anchorName,
    roomTitle = roomTitle,
    enabled = true,
    autoRecord = false,
    quality = "原画",
    mediaType = "视频",
    outputFormat = "MP4",
    flvDirectDownload = false,
    storageDirectoryUri = null,
    segmentEnabled = true,
    segmentSeconds = DEFAULT_SEGMENT_SECONDS,
    scheduledMonitoringEnabled = false,
    notificationsEnabled = true,
    checkIntervalSeconds = MIN_MONITOR_INTERVAL_SECONDS,
    liveStatus = liveStatus,
    taskStatus = TaskStatus.WAITING,
    lastCheckAt = lastCheckAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
