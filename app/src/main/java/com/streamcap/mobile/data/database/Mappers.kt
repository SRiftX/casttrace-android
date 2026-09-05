package com.streamcap.mobile.data.database

import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.RecordingSession

fun LiveRoomEntity.toDomain() = LiveRoom(
    id = id,
    originalUrl = originalUrl,
    normalizedUrl = normalizedUrl,
    platform = platform,
    anchorName = anchorName,
    roomTitle = roomTitle,
    enabled = enabled,
    autoRecord = autoRecord,
    quality = quality,
    mediaType = mediaType,
    outputFormat = outputFormat,
    flvDirectDownload = flvDirectDownload,
    storageDirectoryUri = storageDirectoryUri,
    segmentEnabled = segmentEnabled,
    segmentSeconds = segmentSeconds,
    scheduledMonitoringEnabled = scheduledMonitoringEnabled,
    notificationsEnabled = notificationsEnabled,
    checkIntervalSeconds = checkIntervalSeconds,
    liveStatus = liveStatus,
    taskStatus = taskStatus,
    lastCheckAt = lastCheckAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun RecordingSessionEntity.toDomain() = RecordingSession(
    id = id,
    roomId = roomId,
    roomName = roomName,
    status = status,
    startedAt = startedAt,
    endedAt = endedAt,
    outputUri = outputUri,
    outputUris = outputUris.ifEmpty { listOfNotNull(outputUri) },
    temporaryPath = temporaryPath,
    containerFormat = containerFormat,
    streamType = streamType,
    totalBytes = totalBytes,
    durationSeconds = durationSeconds,
    stopAtMillis = stopAtMillis,
    errorMessage = errorMessage,
)
