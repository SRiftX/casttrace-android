package com.streamcap.mobile.data.repository

import com.streamcap.mobile.data.database.LiveReminderDao
import com.streamcap.mobile.data.database.LiveRoomDao
import com.streamcap.mobile.data.database.RecordingSessionDao
import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.data.database.TaskLogDao
import com.streamcap.mobile.data.database.TaskLogEntity
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveRoomInfo
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.platform.resolver.ResolverRegistry
import com.streamcap.mobile.platform.resolver.ShareLinkDiscovery
import kotlinx.coroutines.flow.Flow

/** Compatibility facade retained while callers migrate to focused repositories. */
class StreamCapRepository(
    private val roomDao: LiveRoomDao,
    private val reminderDao: LiveReminderDao,
    private val sessionDao: RecordingSessionDao,
    private val logDao: TaskLogDao,
    private val resolvers: ResolverRegistry,
    private val shareLinks: ShareLinkDiscovery,
    private val credentialStore: CredentialStore,
    private val taskLogRepository: TaskLogRepository = TaskLogRepository(logDao),
    private val urlResolver: RoomUrlResolver = RoomUrlResolver(shareLinks, resolvers, credentialStore),
    private val roomRepository: RoomRepository = RoomRepository(roomDao, urlResolver, taskLogRepository),
    private val reminderRepository: ReminderRepository = ReminderRepository(
        reminderDao, roomDao, urlResolver, resolvers, credentialStore,
    ),
    private val recordingsRepository: RecordingRepository = RecordingRepository(roomDao, sessionDao),
    private val liveStatusChecker: LiveStatusChecker = LiveStatusChecker(
        roomDao, resolvers, credentialStore, taskLogRepository,
    ),
) {
    val rooms: Flow<List<LiveRoom>> = roomRepository.rooms
    val reminders: Flow<List<LiveReminder>> = reminderRepository.reminders
    val recordings: Flow<List<RecordingSession>> = recordingsRepository.recordings
    val logs: Flow<List<TaskLogEntity>> = taskLogRepository.logs
    val logCount: Flow<Int> = taskLogRepository.logCount
    val recordingEvents: Flow<List<TaskLogEntity>> = taskLogRepository.recordingEvents

    fun observeLogs(limit: Int, level: String?, roomId: Long?, keyOnly: Boolean): Flow<List<TaskLogEntity>> =
        taskLogRepository.observeLogs(limit, level, roomId, keyOnly)

    fun observeLogCount(level: String?, roomId: Long?, keyOnly: Boolean): Flow<Int> =
        taskLogRepository.observeLogCount(level, roomId, keyOnly)

    suspend fun logsForExport(): List<TaskLogEntity> = taskLogRepository.logsForExport()
    fun observeLatestErrorForRoom(roomId: Long): Flow<TaskLogEntity?> = taskLogRepository.observeLatestErrorForRoom(roomId)
    fun observeRecording(id: Long): Flow<RecordingSession?> = recordingsRepository.observeRecording(id)
    fun observeRoom(id: Long): Flow<LiveRoom?> = roomRepository.observeRoom(id)
    suspend fun room(id: Long): LiveRoom? = roomRepository.room(id)

    suspend fun enabledReminders(): List<LiveReminder> = reminderRepository.enabledReminders()
    suspend fun hasEnabledReminders(): Boolean = reminderRepository.hasEnabledReminders()
    suspend fun addReminder(url: String): Boolean = reminderRepository.addReminder(url)
    suspend fun importRoomAsReminder(roomId: Long): Boolean = reminderRepository.importRoomAsReminder(roomId)
    suspend fun setReminderEnabled(reminderId: Long, enabled: Boolean) =
        reminderRepository.setReminderEnabled(reminderId, enabled)
    suspend fun setReminderStrongNotification(reminderId: Long, enabled: Boolean) =
        reminderRepository.setStrongNotificationEnabled(reminderId, enabled)
    suspend fun deleteReminder(reminderId: Long) = reminderRepository.deleteReminder(reminderId)
    suspend fun checkReminder(reminderId: Long, notificationsAllowed: Boolean = true): Boolean =
        reminderRepository.checkReminder(reminderId, notificationsAllowed)

    suspend fun monitoredRooms(): List<LiveRoom> = roomRepository.monitoredRooms()
    suspend fun monitoringDue(room: LiveRoom, now: Long = System.currentTimeMillis()): Boolean =
        roomRepository.monitoringDue(room, now)
    suspend fun setOneTimeWindow(roomId: Long, startAt: Long?, endAt: Long?) =
        roomRepository.setOneTimeWindow(roomId, startAt, endAt)
    suspend fun repairPausedRoomSettings(): Int = roomRepository.repairPausedRoomSettings()
    suspend fun finishInterruptedStatus(roomId: Long, status: TaskStatus) =
        roomRepository.finishInterruptedStatus(roomId, status)
    suspend fun addRoom(
        url: String,
        displayName: String? = null,
        options: RoomRecordingOptions = RoomRecordingOptions(),
    ): Long = roomRepository.addRoom(url, displayName, options)
    suspend fun setEnabled(roomId: Long, enabled: Boolean) = roomRepository.setEnabled(roomId, enabled)
    suspend fun setQuality(roomId: Long, quality: String) = roomRepository.setQuality(roomId, quality)
    suspend fun setScheduledMonitoringEnabled(roomId: Long, enabled: Boolean) =
        roomRepository.setScheduledMonitoringEnabled(roomId, enabled)
    suspend fun updateRecordingOptions(roomId: Long, options: RoomRecordingOptions) =
        roomRepository.updateRecordingOptions(roomId, options)
    suspend fun deleteRoom(roomId: Long) = roomRepository.deleteRoom(roomId)
    suspend fun previewRoom(url: String): Pair<LiveRoomInfo, Boolean> = roomRepository.previewRoom(url)

    suspend fun activeSessionForRoom(roomId: Long): RecordingSessionEntity? =
        recordingsRepository.activeSessionForRoom(roomId)
    suspend fun resetStaleChecks(now: Long = System.currentTimeMillis()): Int =
        liveStatusChecker.resetStaleChecks(now)
    suspend fun refresh(roomId: Long): LiveStatus = liveStatusChecker.refresh(roomId)
    suspend fun streams(roomId: Long): List<StreamSource> = liveStatusChecker.streams(roomId)
    suspend fun streams(reminder: LiveReminder, quality: String = "原画"): List<StreamSource> =
        liveStatusChecker.streams(reminder, quality)
    suspend fun recordingLiveStatus(roomId: Long): LiveStatus = liveStatusChecker.recordingLiveStatus(roomId)

    suspend fun createSession(
        room: LiveRoom,
        source: StreamSource,
        temporaryPath: String,
        stopAtMillis: Long?,
        outputFormat: String,
    ): Long = recordingsRepository.createSession(room, source, temporaryPath, stopAtMillis, outputFormat)

    suspend fun createStartFailureSession(room: LiveRoom, message: String): Long =
        recordingsRepository.createStartFailureSession(room, message)
    suspend fun session(id: Long): RecordingSessionEntity? = recordingsRepository.session(id)
    suspend fun updateSession(session: RecordingSessionEntity) = recordingsRepository.updateSession(session)
    suspend fun deleteSession(session: RecordingSessionEntity) = recordingsRepository.deleteSession(session)
    suspend fun activeSessions(): List<RecordingSessionEntity> = recordingsRepository.activeSessions()
    suspend fun failedPublications(): List<RecordingSessionEntity> = recordingsRepository.failedPublications()
    suspend fun setRoomTaskStatus(roomId: Long, liveStatus: LiveStatus, taskStatus: TaskStatus) =
        recordingsRepository.setRoomTaskStatus(roomId, liveStatus, taskStatus)
    suspend fun log(roomId: Long?, sessionId: Long?, level: String, eventType: String, message: String) =
        taskLogRepository.log(roomId, sessionId, level, eventType, message)
}
