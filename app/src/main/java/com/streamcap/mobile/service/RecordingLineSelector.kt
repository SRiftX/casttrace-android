package com.streamcap.mobile.service

import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.LiveStreamEndedException
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import javax.inject.Inject

data class RecordingLineSelection(
    val source: StreamSource,
    val outputFormat: String,
)

internal object RecordingLinePolicy {
    fun candidates(room: LiveRoom, sources: List<StreamSource>, sourceAttempt: Int): List<StreamSource> {
        val requestedFormat = room.outputFormat.uppercase()
        val huyaMp4 = requestedFormat == "MP4" && room.platform in setOf("huya", "虎牙直播")
        val preferDouyinFlv = room.platform in setOf("douyin", "抖音直播") &&
            requestedFormat in setOf("MP4", "FLV") && sources.any { it.type == StreamType.FLV }
        val preferHuyaFlv = room.platform in setOf("huya", "虎牙直播") &&
            (huyaMp4 || room.flvDirectDownload) && sources.any { it.type == StreamType.FLV }
        val preferHlsAfterFlvFailures = room.platform !in setOf("huya", "虎牙直播") &&
            sourceAttempt >= 3 && sources.any { it.type == StreamType.HLS }
        val preferred = if (preferHlsAfterFlvFailures) {
            sources.filter { it.type == StreamType.HLS }
        } else when (requestedFormat) {
            "FLV" -> sources.filter { it.type == StreamType.FLV }
            "MP4" -> sources.filter { it.type == if (preferHuyaFlv || preferDouyinFlv) StreamType.FLV else StreamType.HLS }
            "TS" -> sources.filter { it.type == StreamType.HLS }
            else -> sources.filter { it.type == if (room.flvDirectDownload) StreamType.FLV else StreamType.HLS }
        }
        return (preferred.ifEmpty {
            val fallbackTypes = if (room.platform in setOf("huya", "虎牙直播")) {
                setOf(StreamType.FLV)
            } else {
                setOf(StreamType.HLS, StreamType.FLV)
            }
            sources.filter { it.type in fallbackTypes }
        }).distinctBy { it.url }
    }

    fun initialIndex(sourceAttempt: Int, candidateCount: Int): Int =
        sourceAttempt.mod(candidateCount.coerceAtLeast(1))

    fun outputFormat(requestedFormat: String, source: StreamSource): String = when {
        requestedFormat == "MP4" -> "MP4"
        source.type == StreamType.FLV -> "FLV"
        source.type == StreamType.HLS -> "TS"
        else -> error("暂不支持录制 ${source.type} 类型的直播源")
    }
}

/** Resolves and ranks playable lines independently from the service lifecycle. */
class RecordingLineSelector @Inject constructor(
    private val repository: StreamCapRepository,
    private val httpClient: OkHttpClient,
) {
    suspend fun availableSources(roomId: Long): List<StreamSource> {
        if (repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) throw LiveStreamEndedException()
        val sources = try {
            repository.streams(roomId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) throw LiveStreamEndedException()
            throw error
        }
        if (sources.isEmpty() && repository.recordingLiveStatus(roomId) == LiveStatus.OFFLINE) {
            throw LiveStreamEndedException()
        }
        return sources
    }

    suspend fun select(
        room: LiveRoom,
        availableSources: List<StreamSource>,
        sourceAttempt: Int,
    ): RecordingLineSelection {
        val requestedFormat = room.outputFormat.uppercase()
        val candidates = RecordingLinePolicy.candidates(room, availableSources, sourceAttempt)
        val initialSource = candidates.getOrNull(RecordingLinePolicy.initialIndex(sourceAttempt, candidates.size))
            ?: error(if (availableSources.isEmpty()) "主播当前未开播，或平台没有返回可用直播源" else "当前线路不提供 $requestedFormat 格式")
        val source = if (initialSource.type == StreamType.HLS) {
            try {
                HlsSourceSelector(httpClient).select(candidates.filter { it.type == StreamType.HLS }, room.quality)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (requestedFormat != "MP4") throw failure
                availableSources.firstOrNull { it.type == StreamType.FLV } ?: throw failure
            }
        } else initialSource

        if (room.quality == "原画" && source.quality != "原画") {
            repository.log(room.id, null, "WARN", "QUALITY_FALLBACK",
                "请求原画但平台返回 ${source.quality} 线路${source.bitrate?.let { "（${it / 1000} kbps）" } ?: ""}，将按实际线路无损保存")
        } else {
            repository.log(room.id, null, "INFO", "QUALITY_SELECTED",
                "已选择 ${source.quality} 线路${source.bitrate?.let { "（${it / 1000} kbps）" } ?: ""}，录制阶段不会转码")
        }
        val outputFormat = RecordingLinePolicy.outputFormat(requestedFormat, source)
        if (outputFormat != requestedFormat) {
            repository.log(room.id, null, "WARN", "RECORDING_FORMAT_FALLBACK",
                "平台当前没有 $requestedFormat 兼容线路，本次使用真实的 $outputFormat 直播源录制")
        }
        return RecordingLineSelection(source, outputFormat)
    }
}
