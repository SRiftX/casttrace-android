package com.streamcap.mobile.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus

@Entity(
    tableName = "live_rooms",
    indices = [Index(value = ["normalizedUrl"], unique = true)],
)
data class LiveRoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String,
    val normalizedUrl: String,
    val platform: String,
    val anchorName: String,
    val roomTitle: String?,
    val enabled: Boolean = true,
    val autoRecord: Boolean = false,
    val quality: String = "原画",
    val mediaType: String = "视频",
    val outputFormat: String = "MP4",
    val flvDirectDownload: Boolean = false,
    val storageDirectoryUri: String? = null,
    val segmentEnabled: Boolean = true,
    val segmentSeconds: Int = 1_800,
    val scheduledMonitoringEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val checkIntervalSeconds: Int = 60,
    val liveStatus: LiveStatus = LiveStatus.UNKNOWN,
    val taskStatus: TaskStatus = TaskStatus.WAITING,
    val lastCheckAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "recording_sessions",
    indices = [Index("roomId")],
)
data class RecordingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val roomName: String,
    val status: TaskStatus = TaskStatus.STARTING,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val outputUri: String? = null,
    val outputUris: List<String> = emptyList(),
    val temporaryPath: String? = null,
    val containerFormat: String = "flv",
    val streamType: StreamType = StreamType.UNKNOWN,
    val totalBytes: Long = 0,
    val durationSeconds: Long = 0,
    val stopAtMillis: Long? = null,
    val errorMessage: String? = null,
)

@Entity(
    tableName = "task_logs",
    indices = [Index("roomId"), Index("sessionId")],
)
data class TaskLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long? = null,
    val sessionId: Long? = null,
    val level: String,
    val eventType: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)
