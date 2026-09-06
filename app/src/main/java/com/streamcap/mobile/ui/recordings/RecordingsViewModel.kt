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
import kotlinx.coroutines.withContext
import com.streamcap.mobile.domain.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.net.Uri
import com.streamcap.mobile.ui.common.formatBytes
import java.io.File

class RecordingsViewModel(
    private val repository: StreamCapRepository,
    private val container: AppContainer,
) : ViewModel() {
    private val _exportProgress = MutableStateFlow<String?>(null)
    val exportProgress = _exportProgress.asStateFlow()
    private val _fileMessage = MutableStateFlow<String?>(null)
    val fileMessage = _fileMessage.asStateFlow()
    private var exportJob: Job? = null

    fun dismissFileMessage() { _fileMessage.value = null }
    fun cancelExport() { exportJob?.cancel() }

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

    fun export(source: String, destination: Uri) {
        if (exportJob?.isActive == true || _exportProgress.value != null) return
        _exportProgress.value = "正在导出"
        exportJob = viewModelScope.launch {
            try {
                var lastReported = 0L
                container.recordingStorage.exportCopy(Uri.parse(source), destination) { bytes ->
                    if (bytes - lastReported >= 1_048_576L) {
                        _exportProgress.value = "正在导出 ${formatBytes(bytes)}"
                        lastReported = bytes
                    }
                }
                _fileMessage.value = "导出完成"
            } catch (cancelled: CancellationException) {
                _fileMessage.value = "导出已取消"
                throw cancelled
            } catch (failure: Exception) {
                _fileMessage.value = "导出失败：${failure.message}"
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
            repository.session(recording.id)?.let { entity ->
                val publishedUris = entity.outputUris.ifEmpty { listOfNotNull(entity.outputUri) }
                val published = container.recordingStorage.deleteRecordingFiles(publishedUris)
                val temporaryDeleted = entity.temporaryPath?.let(::deleteTemporaryFamily) ?: true
                if (published.successful && temporaryDeleted) {
                    repository.deleteSession(entity)
                    afterDelete(null)
                } else {
                    val failedCount = published.failed.size + if (temporaryDeleted) 0 else 1
                    afterDelete("有 $failedCount 个文件删除失败。请先退出正在播放的视频，并确认保存目录仍有删除权限后重试。")
                }
            }
        }
    }

    fun deleteMany(recordings: List<RecordingSession>, afterDelete: (String?) -> Unit = {}) {
        if (recordings.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val errors = recordings.mapNotNull { recording ->
                val entity = repository.session(recording.id) ?: return@mapNotNull "${recording.roomName}: 记录不存在"
                val published = container.recordingStorage.deleteRecordingFiles(entity.outputUris.ifEmpty { listOfNotNull(entity.outputUri) })
                val temporaryDeleted = entity.temporaryPath?.let(::deleteTemporaryFamily) ?: true
                if (published.successful && temporaryDeleted) repository.deleteSession(entity)
                else "${recording.roomName}: 删除失败"
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) { afterDelete(errors.takeIf { it.isNotEmpty() }?.joinToString("；")) }
        }
    }

    private fun deleteTemporaryFamily(path: String): Boolean {
        val first = File(path)
        val stem = Regex("^(.*?)(?:_\\d{3})?(\\.[^.]+)$").matchEntire(first.name)
        val candidates = if (stem == null) listOf(first) else {
            val prefix = stem.groupValues[1]
            val extension = stem.groupValues[2]
            first.parentFile?.listFiles { file ->
                file.name == "$prefix$extension" || Regex("^${Regex.escape(prefix)}_\\d{3}${Regex.escape(extension)}$")
                    .matches(file.name)
            }?.toList().orEmpty().ifEmpty { listOf(first) }
        }
        return candidates.all { file -> !file.exists() || file.delete() }
    }
}
