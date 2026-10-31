package com.streamcap.mobile.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.storage.PreviewCache
import com.streamcap.mobile.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StorageUsage(
    val saved: Long,
    val inaccessible: Int,
    val pending: Long,
    val cache: Long,
    val free: Long,
    val activeBytes: Long,
    val activeSeconds: Long,
)

data class StorageScreenState(
    val usage: StorageUsage? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val result: String? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: StreamCapRepository,
    private val recordingStorage: RecordingStorage,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageScreenState())
    val state: StateFlow<StorageScreenState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(usage = null, error = null)
            try {
                val usage = withContext(Dispatchers.IO) {
                    val sessions = repository.recordings.first()
                    val uris = sessions.flatMap { it.outputUris.ifEmpty { listOfNotNull(it.outputUri) } }.distinct()
                    var inaccessible = 0
                    val saved = uris.sumOf { raw ->
                        val size = runCatching {
                            val uri = Uri.parse(raw)
                            if (uri.scheme == "file") File(requireNotNull(uri.path)).takeIf { it.isFile }?.length()
                            else context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value >= 0 } else null
                            }
                        }.getOrNull()
                        if (size == null) inaccessible++
                        size ?: 0L
                    }
                    val pendingDirectory = File(
                        context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: context.cacheDir,
                        "pending-recordings",
                    )
                    val pending = if (!pendingDirectory.exists()) 0L else requireNotNull(pendingDirectory.listFiles()) {
                        "无法读取待恢复目录"
                    }.filter { it.isFile }.sumOf { it.length() }
                    val active = sessions.filter {
                        it.status in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.FINALIZING)
                    }
                    StorageUsage(
                        saved = saved,
                        inaccessible = inaccessible,
                        pending = pending,
                        cache = PreviewCache.previewFiles(context.cacheDir).sumOf { it.length() },
                        free = recordingStorage.recordingRoot().usableSpace,
                        activeBytes = active.sumOf { it.totalBytes },
                        activeSeconds = active.sumOf { it.durationSeconds },
                    )
                }
                _state.value = _state.value.copy(usage = usage)
            } catch (failure: Exception) {
                _state.value = _state.value.copy(error = "统计失败：${failure.message}，请刷新或检查目录权限")
            }
        }
    }

    fun clearUnusedPreviewCache() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            val result = runCatching {
                val freed = withContext(Dispatchers.IO) { PreviewCache.clearUnused(context.cacheDir) }
                "已释放 ${com.streamcap.mobile.ui.common.formatBytes(freed)}"
            }.getOrElse { "清理未完成：${it.message}" }
            _state.value = _state.value.copy(busy = false, result = result)
            refresh()
        }
    }
}
