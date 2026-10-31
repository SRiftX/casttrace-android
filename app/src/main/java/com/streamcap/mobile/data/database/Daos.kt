package com.streamcap.mobile.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.LiveReminderNotificationPolicy
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveRoomDao {
    @Query("UPDATE live_rooms SET anchorName = CASE WHEN anchorName = :previousName THEN :resolvedName ELSE anchorName END, roomTitle = COALESCE(:title, roomTitle) WHERE id = :id")
    suspend fun updateResolvedInfo(id: Long, previousName: String, resolvedName: String, title: String?)

    @Query("SELECT * FROM live_rooms ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<LiveRoomEntity>>

    @Query("SELECT * FROM live_rooms WHERE id = :id")
    fun observeById(id: Long): Flow<LiveRoomEntity?>

    @Query("SELECT * FROM live_rooms WHERE id = :id")
    suspend fun getById(id: Long): LiveRoomEntity?

    @Query("SELECT * FROM live_rooms WHERE enabled = 1 AND scheduledMonitoringEnabled = 1")
    suspend fun getMonitoredRooms(): List<LiveRoomEntity>

    @Query("UPDATE live_rooms SET scheduledMonitoringEnabled = 0, autoRecord = 0, oneTimeStartAt = NULL, oneTimeEndAt = NULL, updatedAt = :updatedAt WHERE taskStatus = 'PAUSED' AND (scheduledMonitoringEnabled = 1 OR autoRecord = 1 OR oneTimeStartAt IS NOT NULL OR oneTimeEndAt IS NOT NULL)")
    suspend fun repairPausedRoomSettings(updatedAt: Long): Int

    @Query("SELECT * FROM live_rooms WHERE normalizedUrl = :url LIMIT 1")
    suspend fun getByNormalizedUrl(url: String): LiveRoomEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(room: LiveRoomEntity): Long

    @Update
    suspend fun update(room: LiveRoomEntity)

    @Delete
    suspend fun delete(room: LiveRoomEntity)

    @Query("UPDATE live_rooms SET enabled = :enabled, taskStatus = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, status: TaskStatus, updatedAt: Long)

    @Query("UPDATE live_rooms SET quality = :quality, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setQuality(id: Long, quality: String, updatedAt: Long)

    @Query(
        """
        UPDATE live_rooms SET
            scheduledMonitoringEnabled = :enabled,
            autoRecord = :enabled,
            enabled = CASE WHEN :enabled THEN 1 ELSE enabled END,
            taskStatus = CASE WHEN :enabled AND taskStatus IN ('DISABLED', 'PAUSED') THEN 'WAITING' ELSE taskStatus END,
            lastCheckAt = CASE WHEN :enabled THEN NULL ELSE lastCheckAt END,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun setScheduledMonitoringEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE live_rooms SET oneTimeStartAt = :startAt, oneTimeEndAt = :endAt, scheduledMonitoringEnabled = CASE WHEN :startAt IS NOT NULL THEN 1 ELSE scheduledMonitoringEnabled END, autoRecord = CASE WHEN :startAt IS NOT NULL THEN 1 ELSE autoRecord END, taskStatus = CASE WHEN :startAt IS NOT NULL AND taskStatus = 'PAUSED' THEN 'WAITING' ELSE taskStatus END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setOneTimeWindow(id: Long, startAt: Long?, endAt: Long?, updatedAt: Long)

    @Query("UPDATE live_rooms SET oneTimeStartAt = NULL, oneTimeEndAt = NULL, scheduledMonitoringEnabled = 0, autoRecord = 0, updatedAt = :updatedAt WHERE id = :id AND oneTimeEndAt IS NOT NULL AND oneTimeEndAt <= :updatedAt")
    suspend fun expireOneTimeWindow(id: Long, updatedAt: Long)

    @Query(
        """
        UPDATE live_rooms SET
            mediaType = :mediaType,
            outputFormat = :outputFormat,
            quality = :quality,
            flvDirectDownload = :flvDirectDownload,
            storageDirectoryUri = :storageDirectoryUri,
            segmentEnabled = :segmentEnabled,
            segmentSeconds = :segmentSeconds,
            notificationsEnabled = :notificationsEnabled,
            checkIntervalSeconds = :checkIntervalSeconds,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateRecordingOptions(
        id: Long,
        mediaType: String,
        outputFormat: String,
        quality: String,
        flvDirectDownload: Boolean,
        storageDirectoryUri: String?,
        segmentEnabled: Boolean,
        segmentSeconds: Int,
        notificationsEnabled: Boolean,
        checkIntervalSeconds: Int,
        updatedAt: Long,
    )

    @Query("UPDATE live_rooms SET liveStatus = :liveStatus, taskStatus = :taskStatus, scheduledMonitoringEnabled = CASE WHEN :taskStatus = 'PAUSED' THEN 0 ELSE scheduledMonitoringEnabled END, autoRecord = CASE WHEN :taskStatus = 'PAUSED' THEN 0 ELSE autoRecord END, oneTimeStartAt = CASE WHEN :taskStatus = 'PAUSED' THEN NULL ELSE oneTimeStartAt END, oneTimeEndAt = CASE WHEN :taskStatus = 'PAUSED' THEN NULL ELSE oneTimeEndAt END, lastCheckAt = :checkedAt, updatedAt = :checkedAt WHERE id = :id")
    suspend fun setStatus(
        id: Long,
        liveStatus: LiveStatus,
        taskStatus: TaskStatus,
        checkedAt: Long,
    )

    @Query("UPDATE live_rooms SET liveStatus = CASE WHEN liveStatus = 'CHECKING' THEN 'UNKNOWN' ELSE liveStatus END, taskStatus = 'WAITING', updatedAt = :updatedAt WHERE taskStatus = 'CHECKING' AND COALESCE(lastCheckAt, 0) < :cutoff")
    suspend fun resetStaleChecks(cutoff: Long, updatedAt: Long): Int

    @Query("UPDATE live_rooms SET taskStatus = :status, scheduledMonitoringEnabled = CASE WHEN :status = 'PAUSED' THEN 0 ELSE scheduledMonitoringEnabled END, autoRecord = CASE WHEN :status = 'PAUSED' THEN 0 ELSE autoRecord END, oneTimeStartAt = CASE WHEN :status = 'PAUSED' THEN NULL ELSE oneTimeStartAt END, oneTimeEndAt = CASE WHEN :status = 'PAUSED' THEN NULL ELSE oneTimeEndAt END, updatedAt = :updatedAt WHERE id = :id AND taskStatus IN ('STARTING', 'RECORDING', 'RECONNECTING', 'FINALIZING') AND NOT EXISTS (SELECT 1 FROM recording_sessions WHERE roomId = :id AND status IN ('STARTING', 'RECORDING', 'RECONNECTING', 'FINALIZING'))")
    suspend fun finishInterruptedStatus(id: Long, status: TaskStatus, updatedAt: Long)

    @Query("UPDATE live_rooms SET liveStatus = :liveStatus, taskStatus = CASE WHEN taskStatus IN ('PAUSED', 'STARTING', 'RECORDING', 'RECONNECTING', 'FINALIZING') THEN taskStatus ELSE :fallback END, scheduledMonitoringEnabled = CASE WHEN taskStatus = 'PAUSED' OR :fallback = 'PAUSED' THEN 0 ELSE scheduledMonitoringEnabled END, autoRecord = CASE WHEN taskStatus = 'PAUSED' OR :fallback = 'PAUSED' THEN 0 ELSE autoRecord END, oneTimeStartAt = CASE WHEN taskStatus = 'PAUSED' OR :fallback = 'PAUSED' THEN NULL ELSE oneTimeStartAt END, oneTimeEndAt = CASE WHEN taskStatus = 'PAUSED' OR :fallback = 'PAUSED' THEN NULL ELSE oneTimeEndAt END, lastCheckAt = :checkedAt, updatedAt = :checkedAt WHERE id = :id")
    suspend fun setCheckStatus(id: Long, liveStatus: LiveStatus, fallback: TaskStatus, checkedAt: Long)
}

@Dao
interface LiveReminderDao {
    @Query("SELECT * FROM live_reminders ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<LiveReminderEntity>>

    @Query("SELECT * FROM live_reminders WHERE enabled = 1 ORDER BY createdAt DESC, id DESC")
    suspend fun getEnabled(): List<LiveReminderEntity>

    @Query("SELECT * FROM live_reminders WHERE id = :id")
    suspend fun getById(id: Long): LiveReminderEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM live_reminders WHERE normalizedUrl = :url)")
    suspend fun existsByNormalizedUrl(url: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(reminder: LiveReminderEntity): Long

    @Query("UPDATE live_reminders SET enabled = :enabled, notifiedForCurrentLive = CASE WHEN :enabled THEN 0 ELSE notifiedForCurrentLive END, lastCheckAt = CASE WHEN :enabled THEN NULL ELSE lastCheckAt END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE live_reminders SET strongNotificationEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStrongNotificationEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Delete
    suspend fun delete(reminder: LiveReminderEntity)

    @Query("UPDATE live_reminders SET liveStatus = 'CHECKING', updatedAt = :checkedAt WHERE id = :id")
    suspend fun setChecking(id: Long, checkedAt: Long)

    @Query("UPDATE live_reminders SET liveStatus = :status, notifiedForCurrentLive = :notified, lastCheckAt = :checkedAt, updatedAt = :checkedAt WHERE id = :id")
    suspend fun setCheckResult(id: Long, status: LiveStatus, notified: Boolean, checkedAt: Long)

    @Transaction
    suspend fun applyCheckResult(
        id: Long,
        status: LiveStatus,
        checkedAt: Long,
        notificationsAllowed: Boolean,
    ): Boolean {
        val current = getById(id) ?: return false
        val decision = LiveReminderNotificationPolicy.decide(
            current.enabled,
            current.notifiedForCurrentLive,
            status,
            notificationsAllowed,
        )
        setCheckResult(id, status, decision.notifiedForCurrentLive, checkedAt)
        return decision.shouldNotify
    }
}

@Dao
interface RecordingSessionDao {
    @Query(
        """
        SELECT * FROM recording_sessions
        WHERE NOT (status = 'FAILED' AND totalBytes = 0 AND errorMessage LIKE '%正在刷新直播地址并重试%')
        ORDER BY COALESCE(startedAt, 0) DESC
        """,
    )
    fun observeAll(): Flow<List<RecordingSessionEntity>>

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    suspend fun getById(id: Long): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE id = :id")
    fun observeById(id: Long): Flow<RecordingSessionEntity?>

    @Query("SELECT * FROM recording_sessions WHERE roomId = :roomId AND status IN ('STARTING', 'RECORDING', 'RECONNECTING', 'FINALIZING') LIMIT 1")
    suspend fun getActiveForRoom(roomId: Long): RecordingSessionEntity?

    @Query("SELECT * FROM recording_sessions WHERE status IN ('STARTING', 'RECORDING', 'RECONNECTING', 'FINALIZING')")
    suspend fun getActive(): List<RecordingSessionEntity>

    @Query("SELECT * FROM recording_sessions WHERE status = 'FAILED' AND temporaryPath IS NOT NULL AND (errorMessage LIKE '文件发布失败：%' OR errorMessage LIKE '应用进程中断，恢复失败：%')")
    suspend fun getFailedPublications(): List<RecordingSessionEntity>

    @Insert
    suspend fun insert(session: RecordingSessionEntity): Long

    @Transaction
    suspend fun insertIfNoActive(session: RecordingSessionEntity): Long? {
        if (getActiveForRoom(session.roomId) != null) return null
        return insert(session)
    }

    @Update
    suspend fun update(session: RecordingSessionEntity)

    @Delete
    suspend fun delete(session: RecordingSessionEntity)
}

@Dao
interface TaskLogDao {
    @Query("SELECT * FROM task_logs WHERE eventType IN ('INTERRUPTED_RECORDING_RECOVERED', 'STREAM_REFRESH_REQUESTED', 'RECORDING_PARTIAL_SAVED', 'RECORDING_RETRY', 'RECONNECT_WAIT', 'LIVE_ENDED', 'RECORDING_COMPLETED', 'QUALITY_SELECTED', 'QUALITY_FALLBACK') ORDER BY createdAt DESC")
    fun observeRecordingEvents(): Flow<List<TaskLogEntity>>

    @Query("SELECT * FROM task_logs WHERE (:level IS NULL OR level = :level) AND (:roomId IS NULL OR roomId = :roomId) AND (:keyOnly = 0 OR level != 'INFO' OR eventType IN ('RECORDING_START', 'RECORDING_STOP_REASON', 'RECORDING_PAUSED', 'RECORDING_COMPLETED', 'RECORDING_CHECKPOINT_SAVED', 'RECORDING_PART_SAVED', 'RECORDING_SAVE_TIMING', 'LIVE_ENDED', 'RECORDING_FAILED')) ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500, level: String? = null, roomId: Long? = null, keyOnly: Boolean = false): Flow<List<TaskLogEntity>>

    @Query("SELECT COUNT(*) FROM task_logs WHERE (:level IS NULL OR level = :level) AND (:roomId IS NULL OR roomId = :roomId) AND (:keyOnly = 0 OR level != 'INFO' OR eventType IN ('RECORDING_START', 'RECORDING_STOP_REASON', 'RECORDING_PAUSED', 'RECORDING_COMPLETED', 'RECORDING_CHECKPOINT_SAVED', 'RECORDING_PART_SAVED', 'RECORDING_SAVE_TIMING', 'LIVE_ENDED', 'RECORDING_FAILED'))")
    fun observeCount(level: String? = null, roomId: Long? = null, keyOnly: Boolean = false): Flow<Int>

    @Query("SELECT * FROM task_logs ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentForExport(limit: Int = 20_000): List<TaskLogEntity>

    @Query("SELECT * FROM task_logs WHERE roomId = :roomId AND level = 'ERROR' ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestErrorForRoom(roomId: Long): Flow<TaskLogEntity?>

    @Insert
    suspend fun insert(log: TaskLogEntity)

    @Query("DELETE FROM task_logs WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)
}
