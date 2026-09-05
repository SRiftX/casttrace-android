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
import java.io.File

class RecordingsViewModel(
    private val repository: StreamCapRepository,
    private val container: AppContainer,
) : ViewModel() {
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
