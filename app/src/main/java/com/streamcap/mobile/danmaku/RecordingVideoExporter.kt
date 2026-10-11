package com.streamcap.mobile.danmaku

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.ui.player.RecordingPlaybackSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class VideoExportMode { ORIGINAL, WITH_DANMAKU }

class RecordingVideoExporter(private val context: Context, private val repository: StreamCapRepository) {
    suspend fun export(recordingId: Long, source: String, destination: Uri,
                       appearance: DanmakuAppearance, onProgress: (String) -> Unit = {}) {
        val session = requireNotNull(repository.session(recordingId)) { "录制记录不存在" }
        val sources = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.distinct()
        require(destination.toString() !in sources) { "导出位置不能是原文件" }
        try {
            check(session.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.FAILED)) { "录制尚未结束" }
            val index = sources.indexOf(source)
            check(index >= 0) { "文件记录已变化，请刷新后重试" }
            var offset = 0L
            for (part in 0..index) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                RecordingPlaybackSourceResolver(context).use { resolver ->
                    onProgress("正在准备第 ${part + 1} 段")
                    val uri = withContext(Dispatchers.IO) {
                        resolver.resolve(sources[part], listOf(sources[part]), null, session.containerFormat).single()
                    }
                    val duration = withContext(Dispatchers.IO) {
                            val metadata = MediaMetadataRetriever()
                            try {
                                metadata.setDataSource(context, uri)
                                requireNotNull(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()) {
                                    "无法读取片段时长，不能正确同步弹幕"
                                }.also { check(it > 0) { "片段时长不可用" } }
                            } finally { metadata.release() }
                    }
                    if (part < index) offset += duration
                    else {
                        // Include bullets that entered shortly before the cut and remain visible.
                        val events = DanmakuAssetManager(context, repository).eventsWindow(
                            recordingId, (offset - 10_000L).coerceAtLeast(0L), offset + duration, 100_001)
                        check(events.any { it.mediaTimeMs != null }) { "当前视频片段没有可用弹幕" }
                        check(events.size <= 100_000) { "当前片段弹幕超过 10 万条，请缩小导出范围" }
                        DanmakuVideoExporter(context).export(uri, destination, events, appearance, offset, onProgress)
                    }
                }
            }
        } catch (failure: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { com.streamcap.mobile.storage.RecordingStorage(context).deletePublished(destination.toString()) }
            }
            throw failure
        }
    }
}
