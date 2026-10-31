package com.streamcap.mobile.danmaku

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.storage.RecordingNameFormatter
import com.streamcap.mobile.storage.RecordingStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class RecordingShareExporter(
    private val context: Context,
    private val repository: StreamCapRepository,
    private val storage: RecordingStorage = RecordingStorage(context),
) {
    suspend fun prepare(ids: List<Long>, mode: VideoExportMode, appearance: DanmakuAppearance,
                        onProgress: (String) -> Unit = {}): Intent {
        require(ids.isNotEmpty()) { "请先选择录制" }
        val sessions = ids.distinct().map { id ->
            requireNotNull(repository.session(id)) { "录制记录不存在" }.also { session ->
                check(session.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.FAILED)) { "请先结束录制再分享" }
                check(session.outputUris.isNotEmpty() || session.outputUri != null) { "没有可分享的文件" }
                if (mode == VideoExportMode.WITH_DANMAKU) {
                    check(session.danmakuPath != null && session.danmakuEventCount > 0) { "${session.roomName} 没有已保存弹幕" }
                }
            }
        }
        val sources = sessions.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) }.distinct() }
        if (mode == VideoExportMode.ORIGINAL) return withContext(Dispatchers.IO) {
            storage.shareIntent(sources.map(Uri::parse))
        }
        val root = File(context.cacheDir, "shared")
        val directory = File(root, UUID.randomUUID().toString())
        try {
            withContext(Dispatchers.IO) {
                check(root.mkdirs() || root.isDirectory) { "无法创建分享缓存" }
                // Receivers may read asynchronously; keep successful files for seven days.
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                root.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { it.deleteRecursively() }
                check(directory.mkdir()) { "无法创建分享文件夹" }
            }
            val exporter = RecordingVideoExporter(context, repository, storage)
            val shared = mutableListOf<Uri>()
            sessions.forEach { session ->
                session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.distinct().forEachIndexed { index, source ->
                    currentCoroutineContext().ensureActive()
                    val name = "${RecordingNameFormatter.safeComponent(session.roomName)}_${session.id}_${index + 1}_弹幕.mp4"
                    val destination = Uri.fromFile(File(directory, name))
                    val number = shared.size + 1
                    exporter.export(session.id, source, destination, appearance) { onProgress("分享文件 $number/${sources.size} · $it") }
                    shared += destination
                }
            }
            return withContext(Dispatchers.IO) { storage.shareIntent(shared) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { directory.deleteRecursively() }
            throw failure
        }
    }
}
