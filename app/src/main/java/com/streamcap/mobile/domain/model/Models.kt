package com.streamcap.mobile.domain.model

const val MIN_SEGMENT_SECONDS = 1_800
const val MAX_SEGMENT_SECONDS = 604_800

enum class LiveStatus {
    UNKNOWN,
    OFFLINE,
    LIVE,
    CHECKING,
    ERROR,
}

enum class TaskStatus {
    DISABLED,
    WAITING,
    CHECKING,
    STARTING,
    RECORDING,
    RECONNECTING,
    FINALIZING,
    PAUSED,
    COMPLETED,
    FAILED,
}

enum class StreamType {
    HLS,
    FLV,
    DASH,
    UNKNOWN,
}

data class LiveRoom(
    val id: Long,
    val originalUrl: String,
    val normalizedUrl: String,
    val platform: String,
    val anchorName: String,
    val roomTitle: String?,
    val enabled: Boolean,
    val autoRecord: Boolean,
    val quality: String,
    val mediaType: String,
    val outputFormat: String,
    val flvDirectDownload: Boolean,
    val storageDirectoryUri: String?,
    val segmentEnabled: Boolean,
    val segmentSeconds: Int,
    val scheduledMonitoringEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val checkIntervalSeconds: Int,
    val liveStatus: LiveStatus,
    val taskStatus: TaskStatus,
    val lastCheckAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class RoomRecordingOptions(
    val mediaType: String = "视频",
    val outputFormat: String = "MP4",
    val quality: String = "原画",
    val flvDirectDownload: Boolean = false,
    val storageDirectoryUri: String? = null,
    val segmentEnabled: Boolean = true,
    val segmentSeconds: Int = MIN_SEGMENT_SECONDS,
    val scheduledMonitoringEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
)

data class RecordingSession(
    val id: Long,
    val roomId: Long,
    val roomName: String,
    val status: TaskStatus,
    val startedAt: Long?,
    val endedAt: Long?,
    val outputUri: String?,
    val outputUris: List<String>,
    val temporaryPath: String?,
    val containerFormat: String,
    val streamType: StreamType,
    val totalBytes: Long,
    val durationSeconds: Long,
    val stopAtMillis: Long?,
    val errorMessage: String?,
)

data class LiveRoomInfo(
    val normalizedUrl: String,
    val platform: String,
    val anchorName: String,
    val roomTitle: String?,
)

data class StreamSource(
    val url: String,
    val type: StreamType,
    val quality: String,
    val bitrate: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long? = null,
    val requiresFfmpeg: Boolean = false,
)

data class PlatformCredential(
    val platform: String,
    val cookie: String,
)
