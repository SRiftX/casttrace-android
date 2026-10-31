package com.streamcap.mobile.service

import com.streamcap.mobile.data.database.RecordingSessionEntity
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.recording.engine.RecordingProgress
import com.streamcap.mobile.recording.flv.FlvFileInspector
import com.streamcap.mobile.recording.mp4.RecordingMp4Remuxer
import com.streamcap.mobile.recording.repair.RecordingFileRepair
import com.streamcap.mobile.storage.RecordingNameContext
import com.streamcap.mobile.storage.RecordingNameFormatter
import com.streamcap.mobile.storage.RecordingStorage
import com.streamcap.mobile.storage.TemporaryRecordingParts
import dagger.hilt.android.scopes.ServiceScoped
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Serializes expensive publication across normal segment commits and crash recovery. */
internal object RecordingPublicationGate {
    val permit = Semaphore(1)
}

internal data class RecordingPublicationResult(
    val outputUris: List<String>,
)

/** Owns repair, checkpointing, publication, and recovery of closed media files. */
@ServiceScoped
class RecordingPublicationCoordinator @Inject constructor(
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
    private val recordingStorage: RecordingStorage,
) {
    suspend fun publishedDurationSeconds(uris: List<String>): Long = withContext(Dispatchers.IO) {
        recordingStorage.publishedDurationSeconds(uris)
    }

    internal suspend fun publishTerminal(
        recording: ActiveRecording,
        terminal: RecordingProgress,
        session: RecordingSessionEntity,
        paused: Boolean,
        disconnectPaused: Boolean,
        saveStartedAt: Long,
    ): RecordingPublicationResult = withContext(Dispatchers.IO) {
        RecordingPublicationGate.permit.withPermit {
            val startedAt = session.startedAt ?: System.currentTimeMillis()
            val baseName = RecordingNameFormatter.format(
                template = recording.fileNameTemplate,
                includeTitle = recording.includeTitleInFileName,
                removeEmoji = recording.removeEmojiFromFileName,
                context = RecordingNameContext(
                    platform = recording.platform,
                    anchorName = recording.roomName,
                    title = recording.roomTitle,
                    time = LocalDateTime.ofInstant(Instant.ofEpochMilli(startedAt), ZoneId.systemDefault()),
                ),
            )
            val existingFiles = terminal.outputFiles
                .filter { it.exists() && it.length() > 0 && it.absolutePath !in recording.checkpointedFiles }
                .ifEmpty {
                    listOf(recording.temporaryFile).filter {
                        it.exists() && it.length() > 0 && it.absolutePath !in recording.checkpointedFiles
                    }
                }
            val files = if (recording.outputFormat == "FLV" && existingFiles.isNotEmpty()) {
                existingFiles.filter(FlvFileInspector::hasMediaTags).also { playableFiles ->
                    existingFiles.filterNot(playableFiles::contains).forEach(File::delete)
                    check(playableFiles.isNotEmpty()) { "FLV 中没有完整的音视频数据，未保存空文件" }
                }
            } else {
                existingFiles
            }
            files.forEach { file ->
                if (recording.outputFormat == "FLV" || file.extension.equals("flv", true)) {
                    RecordingFileRepair.repairFlv(file)
                } else if (file.extension.equals("ts", true) || file.name.contains(".ts.")) {
                    RecordingFileRepair.repairTransportStream(file)
                }
            }

            val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
            var publishedDuration = recordingStorage.publishedDurationSeconds(outputUris)
            var muxedDurationSeconds = 0L
            var remuxElapsedMillis = 0L
            var publishElapsedMillis = 0L
            var publishedPartCount = 0
            files.forEachIndexed { index, file ->
                val partStartedAt = android.os.SystemClock.elapsedRealtime()
                val indexedOutput = outputUris.isNotEmpty() || files.size > 1
                val partSuffix = if (indexedOutput) "_${(outputUris.size + 1).toString().padStart(3, '0')}" else ""
                val extension = recording.outputFormat.lowercase()
                val publishFile = if (recording.outputFormat == "MP4") {
                    repository.updateSession(session.copy(
                        status = TaskStatus.FINALIZING,
                        outputUri = outputUris.firstOrNull(),
                        outputUris = outputUris.toList(),
                        errorMessage = "正在修复索引${if (files.size > 1) "（第 ${index + 1}/${files.size} 段）" else ""}",
                    ))
                    File(file.parentFile, "${file.nameWithoutExtension}.mp4").also { target ->
                        val tracks = RecordingMp4Remuxer.convert(file, target) { ffmpegError ->
                            repository.log(
                                recording.roomId,
                                recording.sessionId,
                                "WARN",
                                "FFMPEG_MP4_MUX_FAILED",
                                "FFmpeg 封装失败，已切换系统封装器：${ffmpegError.message}",
                            )
                        }
                        muxedDurationSeconds += tracks.durationSeconds
                        remuxElapsedMillis += android.os.SystemClock.elapsedRealtime() - partStartedAt
                        android.util.Log.i(
                            "StreamCapSave",
                            "remux room=${recording.roomId} part=${index + 1} bytes=${file.length()} elapsed=${android.os.SystemClock.elapsedRealtime() - partStartedAt}ms",
                        )
                        if (tracks.audioTracks == 0) {
                            repository.log(
                                recording.roomId,
                                recording.sessionId,
                                "WARN",
                                "RECORDING_AUDIO_TRACK_MISSING",
                                "MP4 封装完成但没有检测到音频轨道，可能是平台线路只返回视频或音频编码不受设备支持",
                            )
                        }
                    }
                } else {
                    file
                }
                repository.updateSession(session.copy(
                    status = TaskStatus.FINALIZING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    errorMessage = "正在保存${if (files.size > 1) "（第 ${index + 1}/${files.size} 段）" else ""}",
                ))
                val publishStartedAt = android.os.SystemClock.elapsedRealtime()
                val outputUri = recordingStorage.publish(
                    publishFile,
                    "$baseName$partSuffix.$extension",
                    recording.outputFormat.mimeType(),
                    recording.storageDirectoryUri,
                    recording.roomName,
                    retainTemporary = true,
                    publicationKey = file.absolutePath,
                )
                val partPublishMillis = android.os.SystemClock.elapsedRealtime() - publishStartedAt
                publishElapsedMillis += partPublishMillis
                publishedPartCount++
                repository.log(
                    recording.roomId,
                    recording.sessionId,
                    "INFO",
                    "RECORDING_PART_SAVED",
                    "第 ${outputUris.size + 1} 段已保存，文件 ${publishFile.length() / 1_048_576} MB，封装 ${android.os.SystemClock.elapsedRealtime() - partStartedAt - partPublishMillis} 毫秒，写入 ${partPublishMillis} 毫秒",
                )
                android.util.Log.i(
                    "StreamCapSave",
                    "publish room=${recording.roomId} part=${index + 1} bytes=${publishFile.length()} elapsed=${android.os.SystemClock.elapsedRealtime() - partStartedAt}ms",
                )
                outputUris += outputUri
                publishedDuration += recordingStorage.publishedDurationSeconds(listOf(outputUri))
                repository.updateSession(session.copy(
                    status = TaskStatus.FINALIZING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    totalBytes = terminal.bytesWritten,
                    durationSeconds = publishedDuration.takeIf { it > 0L }
                        ?: (muxedDurationSeconds.takeIf { it > 0L } ?: terminal.durationSeconds),
                    errorMessage = "已保存 ${outputUris.size}/${files.size} 个分段",
                ))
                if (publishFile != file) publishFile.delete()
                file.delete()
                recordingStorage.acknowledgePublication(file.absolutePath)
            }

            val finalDurationSeconds = publishedDuration.takeIf { it > 0L }
                ?: muxedDurationSeconds.takeIf { recording.outputFormat == "MP4" && it > 0L }
                ?: terminal.durationSeconds
            repository.updateSession(session.copy(
                status = if (paused) TaskStatus.PAUSED else TaskStatus.COMPLETED,
                endedAt = System.currentTimeMillis(),
                outputUri = outputUris.firstOrNull(),
                outputUris = outputUris.toList(),
                totalBytes = terminal.bytesWritten,
                durationSeconds = finalDurationSeconds,
                errorMessage = if (disconnectPaused) "断流已暂停，已保存当前内容" else null,
            ))
            val elapsed = android.os.SystemClock.elapsedRealtime() - saveStartedAt
            android.util.Log.i("StreamCapSave", "saved room=${recording.roomId} elapsed=${android.os.SystemClock.elapsedRealtime() - saveStartedAt}ms")
            repository.log(
                recording.roomId,
                recording.sessionId,
                "INFO",
                "RECORDING_SAVE_TIMING",
                "本次保存 ${publishedPartCount} 段；总耗时 ${elapsed} 毫秒，MP4 封装 ${remuxElapsedMillis} 毫秒，文件写入 ${publishElapsedMillis} 毫秒",
            )
            RecordingPublicationResult(outputUris.toList())
        }
    }

    internal suspend fun checkpointCompletedParts(recording: ActiveRecording, completed: List<File>) = withContext(Dispatchers.IO) {
        if (completed.isEmpty() || completed.none { it.absolutePath !in recording.checkpointedFiles }) return@withContext
        RecordingPublicationGate.permit.withPermit {
            var session = repository.session(recording.sessionId) ?: return@withPermit
            val room = repository.room(recording.roomId) ?: return@withPermit
            val settings = preferences.settings.first()
            val baseName = RecordingNameFormatter.format(
                template = recording.fileNameTemplate,
                includeTitle = recording.includeTitleInFileName,
                removeEmoji = recording.removeEmojiFromFileName,
                context = RecordingNameContext(
                    platform = recording.platform,
                    anchorName = recording.roomName,
                    title = recording.roomTitle,
                    time = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(session.startedAt ?: System.currentTimeMillis()),
                        ZoneId.systemDefault(),
                    ),
                ),
            )
            val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
            completed.distinctBy { it.absolutePath }.forEach { file ->
                val key = file.absolutePath
                if (key in recording.checkpointedFiles || !file.exists() || file.length() <= 0L) return@forEach
                val expectedPart = outputUris.size + 1
                if (TemporaryRecordingParts.index(recording.temporaryFile, file) != expectedPart) return@withPermit
                try {
                    if (recording.outputFormat == "FLV" || file.extension.equals("flv", true)) {
                        RecordingFileRepair.repairFlv(file)
                    } else if (file.extension.equals("ts", true) || file.name.contains(".ts.")) {
                        RecordingFileRepair.repairTransportStream(file)
                    }
                    val index = outputUris.size + 1
                    val extension = recording.outputFormat.lowercase()
                    val publishFile = if (recording.outputFormat == "MP4") {
                        File(file.parentFile, "${file.nameWithoutExtension}.mp4").also { target ->
                            RecordingMp4Remuxer.convert(file, target)
                        }
                    } else {
                        file
                    }
                    val uri = recordingStorage.publish(
                        publishFile,
                        "${baseName}_${index.toString().padStart(3, '0')}.$extension",
                        recording.outputFormat.mimeType(),
                        recording.storageDirectoryUri ?: room.storageDirectoryUri ?: settings.storageDirectoryUri,
                        recording.roomName,
                        retainTemporary = true,
                        publicationKey = file.absolutePath,
                    )
                    outputUris += uri
                    session = session.copy(
                        status = TaskStatus.RECORDING,
                        outputUri = outputUris.firstOrNull(),
                        outputUris = outputUris.toList(),
                        errorMessage = "录制中已保存 ${outputUris.size} 个分段",
                    )
                    // Keep the recovery source until the updated URI list is durable.
                    repository.updateSession(session)
                    recording.checkpointedFiles += key
                    if (publishFile != file) publishFile.delete()
                    file.delete()
                    recordingStorage.acknowledgePublication(file.absolutePath)
                    repository.log(
                        recording.roomId,
                        recording.sessionId,
                        "INFO",
                        "RECORDING_CHECKPOINT_SAVED",
                        "录制中已保存第 ${outputUris.size} 个分段",
                    )
                } catch (failure: Throwable) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    repository.log(
                        recording.roomId,
                        recording.sessionId,
                        "WARN",
                        "RECORDING_CHECKPOINT_FAILED",
                        "分段暂未发布，将在录制结束或恢复时重试：${failure.message}",
                    )
                    return@withPermit
                }
            }
        }
    }

    internal suspend fun recoverInterruptedSession(session: RecordingSessionEntity): Boolean =
        withContext(Dispatchers.IO) { RecordingPublicationGate.permit.withPermit {
            val first = session.temporaryPath?.let(::File) ?: return@withPermit false
            val outputUris = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.toMutableList()
            val files = TemporaryRecordingParts.family(first).filter {
                it.exists() && it.length() > 0L && (TemporaryRecordingParts.index(first, it) ?: 0) > outputUris.size
            }
            val recoveredBytes = maxOf(session.totalBytes, files.sumOf { it.length() })
            val publishedDuration = recordingStorage.publishedDurationSeconds(outputUris)
            if (files.isEmpty()) {
                if (outputUris.isEmpty()) return@withPermit false
                repository.updateSession(session.copy(
                    status = TaskStatus.COMPLETED,
                    endedAt = System.currentTimeMillis(),
                    outputUri = outputUris.first(),
                    outputUris = outputUris.toList(),
                    durationSeconds = publishedDuration.takeIf { it > 0L } ?: session.durationSeconds,
                    errorMessage = "应用进程中断，已保留已保存的 ${outputUris.size} 个分段",
                ))
                return@withPermit true
            }
            val room = repository.room(session.roomId) ?: return@withPermit false
            val settings = preferences.settings.first()
            val baseName = RecordingNameFormatter.format(
                template = settings.fileNameTemplate,
                includeTitle = settings.includeTitleInFileName,
                removeEmoji = settings.removeEmojiFromFileName,
                context = RecordingNameContext(
                    room.platform,
                    session.roomName,
                    session.roomTitle,
                    LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(session.startedAt ?: System.currentTimeMillis()),
                        ZoneId.systemDefault(),
                    ),
                ),
            )
            var duration = publishedDuration
            val totalParts = outputUris.size + files.size
            files.forEach { file ->
                check(TemporaryRecordingParts.index(first, file) == outputUris.size + 1) {
                    "分段序号不连续，未发布的源文件已保留"
                }
                if (session.streamType == StreamType.FLV || file.extension.equals("flv", true)) {
                    RecordingFileRepair.repairFlv(file)
                } else {
                    RecordingFileRepair.repairTransportStream(file)
                }
                val suffix = if (totalParts > 1) "_${(outputUris.size + 1).toString().padStart(3, '0')}" else ""
                val published = if (session.containerFormat.equals("mp4", true)) {
                    val target = File(file.parentFile, "${file.nameWithoutExtension}.mp4")
                    val tracks = RecordingMp4Remuxer.convert(file, target)
                    duration += tracks.durationSeconds
                    recordingStorage.publish(
                        target,
                        "$baseName$suffix.mp4",
                        "video/mp4",
                        room.storageDirectoryUri ?: settings.storageDirectoryUri,
                        session.roomName,
                        retainTemporary = true,
                        publicationKey = file.absolutePath,
                    ).also { target.delete() }
                } else {
                    val extension = session.containerFormat.lowercase()
                    recordingStorage.publish(
                        file,
                        "$baseName$suffix.$extension",
                        extension.mimeType(),
                        room.storageDirectoryUri ?: settings.storageDirectoryUri,
                        session.roomName,
                        retainTemporary = true,
                        publicationKey = file.absolutePath,
                    )
                }
                outputUris += published
                if (!session.containerFormat.equals("mp4", true)) {
                    duration += recordingStorage.publishedDurationSeconds(listOf(published))
                }
                repository.updateSession(session.copy(
                    status = TaskStatus.FINALIZING,
                    outputUri = outputUris.firstOrNull(),
                    outputUris = outputUris.toList(),
                    totalBytes = recoveredBytes,
                    durationSeconds = duration.takeIf { it > 0L } ?: session.durationSeconds,
                    errorMessage = "应用进程中断，已自动保存 ${outputUris.size} 个分段",
                ))
                file.delete()
                recordingStorage.acknowledgePublication(file.absolutePath)
            }
            repository.updateSession(session.copy(
                status = TaskStatus.COMPLETED,
                endedAt = System.currentTimeMillis(),
                outputUri = outputUris.firstOrNull(),
                outputUris = outputUris,
                totalBytes = recoveredBytes,
                durationSeconds = duration.takeIf { it > 0L } ?: session.durationSeconds,
                errorMessage = "应用进程中断，已自动保存 ${outputUris.size} 个分段",
            ))
            outputUris.isNotEmpty()
        } }
}

private fun String.mimeType(): String = when (uppercase()) {
    "FLV" -> "video/x-flv"
    "TS" -> "video/mp2t"
    "MP4" -> "video/mp4"
    "MKV" -> "video/x-matroska"
    else -> "video/mp4"
}
