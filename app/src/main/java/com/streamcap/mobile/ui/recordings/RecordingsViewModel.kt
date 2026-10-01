package com.streamcap.mobile.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.app.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import com.streamcap.mobile.ui.common.formatBytes
import java.io.File
import com.streamcap.mobile.danmaku.VideoExportMode
import kotlinx.coroutines.flow.first
import android.content.Intent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.streamcap.mobile.storage.RecordingStorage

class RecordingsViewModel(
    private val repository: StreamCapRepository,
    private val container: AppContainer,
) : ViewModel() {
    private val _exportProgress = MutableStateFlow<String?>(null)
    val exportProgress = _exportProgress.asStateFlow()
    private val _fileMessage = MutableStateFlow<String?>(null)
    val fileMessage = _fileMessage.asStateFlow()
    val exportDestination = MutableStateFlow<Uri?>(null)
    private var exportJob: Job? = null
    private val shareRequests = Channel<Intent>(Channel.BUFFERED)
    val shareReady = shareRequests.receiveAsFlow()
    private val deleteMutex = Mutex()
    private val _deleteAuthorization = MutableStateFlow<RecordingStorage.DeleteAuthorization?>(null)
    val deleteAuthorization = _deleteAuthorization.asStateFlow()
    private var deleteAuthorizationAnswer: CompletableDeferred<Boolean>? = null

    fun completeDeleteAuthorization(granted: Boolean) { deleteAuthorizationAnswer?.complete(granted) }

    private suspend fun authorizeDeletion(request: RecordingStorage.DeleteAuthorization): Boolean {
        val answer = CompletableDeferred<Boolean>()
        deleteAuthorizationAnswer = answer
        _deleteAuthorization.value = request
        return try {
            kotlinx.coroutines.withTimeoutOrNull(180_000) { answer.await() } == true
        } finally {
            _deleteAuthorization.value = null
            deleteAuthorizationAnswer = null
        }
    }

    fun dismissFileMessage() { _fileMessage.value = null }
    fun cancelExport() { exportJob?.cancel() }

    fun share(ids: List<Long>, mode: VideoExportMode) {
        if (exportJob?.isActive == true || _exportProgress.value != null) return
        exportDestination.value = null
        _fileMessage.value = null
        _exportProgress.value = "正在准备分享"
        exportJob = viewModelScope.launch {
            try {
                val intent = container.shareExporter.prepare(
                    ids, mode, container.preferences.danmakuAppearance.first()) { _exportProgress.value = it }
                shareRequests.send(intent)
            } catch (cancelled: CancellationException) {
                _fileMessage.value = "分享已取消，未完成的临时文件已清理，原录制保留"
                throw cancelled
            } catch (failure: Exception) {
                _fileMessage.value = "分享失败：${failure.message}"
            } finally {
                _exportProgress.value = null
            }
        }
    }

    fun rename(recordingId: Long, source: String, baseName: String) {
        if (exportJob?.isActive == true || _exportProgress.value != null) return
        _exportProgress.value = "正在重命名"
        viewModelScope.launch {
            try {
                // A document rename can change its URI; persist it before honoring cancellation.
                val name = withContext(Dispatchers.IO + NonCancellable) {
                    val session = requireNotNull(repository.session(recordingId)) { "录制记录不存在" }
                    check(session.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.FAILED)) { "录制尚未结束" }
                    val sources = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
                    check(source in sources) { "文件记录已变化，请刷新后重试" }
                    val renamed = container.recordingStorage.renamePublished(Uri.parse(source), baseName)
                    repository.updateSession(session.copy(
                        outputUris = sources.map { if (it == source) renamed.toString() else it },
                        outputUri = if (session.outputUri == source) renamed.toString() else session.outputUri,
                    ))
                    container.recordingStorage.displayName(renamed)
                }
                _fileMessage.value = "已重命名为 $name"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _fileMessage.value = "重命名失败：${failure.message}"
            } finally {
                _exportProgress.value = null
            }
        }
    }

    fun export(source: String, destination: Uri, recordingId: Long? = null, mode: VideoExportMode = VideoExportMode.ORIGINAL) {
        if (exportJob?.isActive == true || _exportProgress.value != null) return
        exportDestination.value = null
        _exportProgress.value = "正在导出"
        exportJob = viewModelScope.launch {
            try {
                var lastReported = 0L
                if (mode == VideoExportMode.WITH_DANMAKU) {
                    container.videoExporter.export(requireNotNull(recordingId), source, destination,
                        container.preferences.danmakuAppearance.first()) { _exportProgress.value = it }
                } else container.recordingStorage.exportCopy(Uri.parse(source), destination) { bytes ->
                    if (bytes - lastReported >= 1_048_576L) {
                        _exportProgress.value = "正在导出 ${formatBytes(bytes)}"
                        lastReported = bytes
                    }
                }
                _fileMessage.value = "导出完成，文件已保存到所选位置"
            } catch (cancelled: CancellationException) {
                _fileMessage.value = "导出已取消，未完成的目标文件已清理；原录制保留，可重新导出"
                throw cancelled
            } catch (failure: Exception) {
                _fileMessage.value = "导出失败：${failure.message}"
            } finally {
                _exportProgress.value = null
            }
        }
    }

    fun exportAll(recordingId: Long, directory: Uri, mode: VideoExportMode = VideoExportMode.ORIGINAL) {
        if (exportJob?.isActive == true || _exportProgress.value != null) return
        exportDestination.value = directory
        _exportProgress.value = "正在导出整场"
        exportJob = viewModelScope.launch {
            var completed = 0
            try {
                val session = requireNotNull(repository.session(recordingId)) { "录制记录不存在" }
                check(session.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.FAILED)) { "录制尚未结束" }
                val sources = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }.distinct()
                check(sources.isNotEmpty()) { "没有可导出的分段" }
                val appearance = container.preferences.danmakuAppearance.first()
                withContext(Dispatchers.IO) {
                    val folder = container.recordingStorage.exportDirectory(directory)
                    check(folder.canWrite()) { "导出目录无写入权限" }
                    sources.forEachIndexed { index, source ->
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val base = com.streamcap.mobile.storage.RecordingNameFormatter.safeComponent(session.roomName)
                        val name = if (mode == VideoExportMode.WITH_DANMAKU) "${base}_${session.id}_${index + 1}_弹幕.mp4"
                            else "${base}_${session.id}_${index + 1}.${session.containerFormat.lowercase()}"
                        val existing = folder.findFile(name)
                        if (existing != null) {
                            check(mode == VideoExportMode.ORIGINAL) { "带弹幕同名文件已存在：$name，请选择其他目录或先重命名该文件" }
                            _exportProgress.value = "正在核验已有第 ${index + 1}/${sources.size} 段"
                            check(container.recordingStorage.sameContents(Uri.parse(source), existing.uri)) {
                                "同名文件内容不同：$name，未覆盖，请选择其他目录"
                            }
                            completed++
                            return@forEachIndexed
                        }
                        val mime = if (mode == VideoExportMode.WITH_DANMAKU) "video/mp4" else when (session.containerFormat.uppercase()) {
                            "MP4" -> "video/mp4"
                            "TS" -> "video/mp2t"
                            "FLV" -> "video/x-flv"
                            else -> "application/octet-stream"
                        }
                        val destination = requireNotNull(folder.createFile(mime, name)) { "无法创建导出文件" }
                        try {
                            if (mode == VideoExportMode.WITH_DANMAKU) {
                                container.videoExporter.export(recordingId, source, destination.uri, appearance) {
                                    _exportProgress.value = "第 ${index + 1}/${sources.size} 段 · $it"
                                }
                            } else container.recordingStorage.exportCopy(Uri.parse(source), destination.uri) { bytes ->
                                _exportProgress.value = "正在导出 ${index + 1}/${sources.size} 段 · ${formatBytes(bytes)}"
                            }
                        } catch (failure: Throwable) {
                            // Cancellation can occur before exportCopy enters its IO block.
                            runCatching { destination.delete() }
                            throw failure
                        }
                        completed++
                    }
                }
                _fileMessage.value = "整场导出完成，共 $completed 段"
            } catch (cancelled: CancellationException) {
                _fileMessage.value = "导出已取消，已完成 $completed 段已保留在所选目录" +
                    if (mode == VideoExportMode.ORIGINAL) "，可选择同目录重试" else "；重新导出请选择其他目录"
                throw cancelled
            } catch (failure: Exception) {
                _fileMessage.value = "导出失败，已完成 $completed 段已保留在所选目录：${failure.message}"
            } finally {
                _exportProgress.value = null
            }
        }
    }

    val recordings: StateFlow<List<RecordingSession>> = repository.recordings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun delete(recording: RecordingSession, afterDelete: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val error = deleteMutex.withLock { withContext(Dispatchers.IO) { deleteSavedRecording(recording.id) } }
            afterDelete(error)
        }
    }

    private suspend fun deleteSavedRecording(id: Long): String? {
        return try {
            val entity = repository.session(id)
            if (entity == null) null else {
                check(entity.status in setOf(TaskStatus.COMPLETED, TaskStatus.PAUSED, TaskStatus.FAILED)) {
                    "录制尚未结束，请先暂停后删除"
                }
                val uris = entity.outputUris.ifEmpty { listOfNotNull(entity.outputUri) }
                var published = container.recordingStorage.deleteRecordingFiles(uris)
                val authorizedTargets = mutableSetOf<List<String>>()
                val deletedBySystem = mutableSetOf<String>()
                while (!published.successful && published.authorization != null) {
                    if (!authorizedTargets.add(published.failed.sorted())) break
                    val authorization = requireNotNull(published.authorization)
                    if (!authorizeDeletion(authorization)) return "未授权删除，录制记录和恢复文件已保留"
                    deletedBySystem.addAll(authorization.deletedBySystem)
                    published = container.recordingStorage.deleteRecordingFiles(uris, deletedBySystem)
                }
                if (!published.successful) return "部分文件删除失败，请恢复原保存目录的读写权限后重试；录制记录和恢复文件已保留"
                val temporaryDeleted = entity.temporaryPath?.let(::deleteTemporaryFamily) ?: true
                val danmakuDeleted = if (temporaryDeleted) entity.danmakuPath?.let { path ->
                    val file = File(path)
                    val removed = !file.exists() || file.delete()
                    if (removed) {
                        File(file.parentFile, "committed.marker").delete()
                        File(file.parentFile, "coverage.json").delete()
                        file.parentFile?.delete()
                    }
                    removed
                } ?: true else true
                if (temporaryDeleted && danmakuDeleted) {
                    repository.deleteSession(entity)
                    null
                } else "部分文件删除失败，请检查目录权限后重试；已删除的文件不会重复报错"
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "删除失败：${failure.message}"
        }
    }

    fun deleteMany(recordings: List<RecordingSession>, afterDelete: (String?) -> Unit = {}) {
        if (recordings.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            deleteMutex.withLock {
                val errors = recordings.mapNotNull { recording ->
                    deleteSavedRecording(recording.id)?.let { "${recording.roomName}: $it" }
                }
                withContext(Dispatchers.Main) { afterDelete(errors.takeIf { it.isNotEmpty() }?.joinToString("；")) }
            }
        }
    }

    private fun deleteTemporaryFamily(path: String): Boolean {
        val first = File(path)
        val stem = Regex("^(.*?)(?:_\\d{3})?(\\.(?:flv|ts)\\.part|\\.[^.]+)$").matchEntire(first.name)
        val candidates = if (stem == null) listOf(first) else {
            val prefix = stem.groupValues[1]
            val extension = stem.groupValues[2]
            first.parentFile?.listFiles { file ->
                file.name == "$prefix$extension" || Regex("^${Regex.escape(prefix)}_\\d{3}${Regex.escape(extension)}$")
                    .matches(file.name)
            }?.toList().orEmpty().ifEmpty { listOf(first) }
        }
        return candidates.map { file ->
            container.recordingStorage.discardPublication(file.absolutePath) && (!file.exists() || file.delete())
        }.all { it }
    }
}
