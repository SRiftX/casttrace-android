package com.streamcap.mobile.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveRoomDao {
    @Query("UPDATE live_rooms SET anchorName = CASE WHEN anchorName = :previousName THEN :resolvedName ELSE anchorName END, roomTitle = COALESCE(:title, roomTitle) WHERE id = :id")
    suspend fun updateResolvedInfo(id: Long, previousName: String, resolvedName: String, title: String?)

    @Query("SELECT * FROM live_rooms ORDER BY CASE WHEN taskStatus = 'RECORDING' THEN 0 ELSE 1 END, updatedAt DESC")
    fun observeAll(): Flow<List<LiveRoomEntity>>

    @Query("SELECT * FROM live_rooms WHERE id = :id")
    fun observeById(id: Long): Flow<LiveRoomEntity?>

    @Query("SELECT * FROM live_rooms WHERE id = :id")
    suspend fun getById(id: Long): LiveRoomEntity?

    @Query("SELECT * FROM live_rooms WHERE enabled = 1 AND scheduledMonitoringEnabled = 1")
    suspend fun getMonitoredRooms(): List<LiveRoomEntity>

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
            taskStatus = CASE WHEN :enabled THEN 'WAITING' ELSE taskStatus END,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun setScheduledMonitoringEnabled(id: Long, enabled: Boolean, updatedAt: Long)

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
            scheduledMonitoringEnabled = :scheduledMonitoringEnabled,
            autoRecord = :scheduledMonitoringEnabled,
            notificationsEnabled = :notificationsEnabled,
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
        scheduledMonitoringEnabled: Boolean,
        notificationsEnabled: Boolean,
        updatedAt: Long,
    )

    @Query("UPDATE live_rooms SET liveStatus = :liveStatus, taskStatus = :taskStatus, lastCheckAt = :checkedAt, updatedAt = :checkedAt WHERE id = :id")
    suspend fun setStatus(
        id: Long,
        liveStatus: LiveStatus,
        taskStatus: TaskStatus,
        checkedAt: Long,
    )
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

    @Insert
    suspend fun insert(session: RecordingSessionEntity): Long

    @Update
    suspend fun update(session: RecordingSessionEntity)

    @Delete
    suspend fun delete(session: RecordingSessionEntity)
}

@Dao
interface TaskLogDao {
    @Query("SELECT * FROM task_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<TaskLogEntity>>

    @Insert
    suspend fun insert(log: TaskLogEntity)

    @Query("DELETE FROM task_logs WHERE createdAt < :before")
    suspend fun deleteBefore(before: Long)
}
