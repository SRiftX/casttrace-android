package com.streamcap.mobile.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.MAX_SEGMENT_SECONDS
import com.streamcap.mobile.domain.model.MIN_SEGMENT_SECONDS
import com.streamcap.mobile.data.preferences.RecordingTemplate
import com.streamcap.mobile.domain.model.LiveRoomInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportLine(
    val original: String,
    val url: String?,
    val info: LiveRoomInfo? = null,
    val status: String,
)

data class BatchImportResult(val added: Int, val skipped: Int, val failed: List<String>)

data class AddRoomState(
    val url: String = "",
    val displayName: String = "",
    val mediaType: String = "视频",
    val outputFormat: String = "MP4",
    val quality: String = "原画",
    val flvDirectDownload: Boolean = true,
    val storageDirectoryUri: String? = null,
    val segmentEnabled: Boolean = true,
    val segmentMinutes: String = "30",
    val scheduledMonitoringEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val checkIntervalSeconds: String = "60",
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedRoomId: Long? = null,
    val sharedText: String? = null,
    val preview: LiveRoomInfo? = null,
    val previewDuplicate: Boolean = false,
    val batchText: String = "",
    val batchPreview: List<ImportLine> = emptyList(),
    val batchBusy: Boolean = false,
    val batchResult: BatchImportResult? = null,
)

class AddRoomViewModel(
    private val repository: StreamCapRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(AddRoomState())
    val state: StateFlow<AddRoomState> = _state

    init {
        viewModelScope.launch {
            val defaults = preferences.settings.first()
            _state.update {
                it.copy(
                    outputFormat = defaults.defaultFormat.takeIf { value -> value in setOf("FLV", "TS", "MP4") } ?: "MP4",
                    quality = defaults.defaultQuality,
                    storageDirectoryUri = defaults.storageDirectoryUri,
                    checkIntervalSeconds = defaults.checkIntervalSeconds.coerceIn(15, 3_600).toString(),
                )
            }
        }
    }

    fun setInitialUrl(url: String?) {
        if (!url.isNullOrBlank() && _state.value.url.isBlank()) {
            val extracted = RoomLinkInput.extract(url)
            _state.update { it.copy(url = extracted ?: url.trim(), sharedText = url.takeIf { extracted == null }) }
            if (extracted != null) preview()
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, preview = null, previewDuplicate = false, error = null) }

    fun preview() {
        val input = RoomLinkInput.extract(_state.value.url) ?: _state.value.url.trim()
        if (input.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            try {
                val (info, duplicate) = withContext(Dispatchers.IO) { repository.previewRoom(input) }
                _state.update { it.copy(url = info.normalizedUrl, preview = info, previewDuplicate = duplicate,
                    displayName = it.displayName.ifBlank { info.anchorName }, isSaving = false) }
            } catch (failure: Exception) {
                _state.update { it.copy(error = failure.message ?: "链接解析失败", isSaving = false) }
            }
        }
    }

    fun setBatchText(value: String) = _state.update { it.copy(batchText = value, batchPreview = emptyList(), batchResult = null) }

    fun previewBatch() {
        if (RoomLinkInput.exceedsLimit(_state.value.batchText)) {
            _state.update { it.copy(error = "一次最多导入 200 行，请拆分后重试") }
            return
        }
        val lines = RoomLinkInput.lines(_state.value.batchText)
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(batchBusy = true, batchPreview = emptyList(), batchResult = null) }
            val seen = mutableSetOf<String>()
            val result = withContext(Dispatchers.IO) {
                lines.map { original ->
                    val url = RoomLinkInput.extract(original)
                    if (url == null) ImportLine(original, null, status = "格式错误：没有链接")
                    else try {
                        val (info, duplicate) = repository.previewRoom(url)
                        val repeated = !seen.add(info.normalizedUrl)
                        ImportLine(original, url, info, when {
                            duplicate -> "已存在"
                            repeated -> "本次重复"
                            else -> "可添加"
                        })
                    } catch (failure: Exception) {
                        ImportLine(original, url, status = failure.message ?: "解析失败")
                    }
                }
            }
            _state.update { it.copy(batchBusy = false, batchPreview = result, error = null) }
        }
    }

    fun importBatch() {
        val candidates = _state.value.batchPreview.filter { it.status == "可添加" }
        if (candidates.isEmpty() || _state.value.batchBusy) return
        viewModelScope.launch {
            _state.update { it.copy(batchBusy = true) }
            var added = 0
            val failed = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                candidates.forEach { line ->
                    try {
                        repository.addRoom(requireNotNull(line.url))
                        added++
                    } catch (failure: Exception) {
                        failed += "${line.original}：${failure.message ?: "添加失败"}"
                    }
                }
            }
            val preview = _state.value.batchPreview
            val skipped = preview.count { it.status == "已存在" || it.status == "本次重复" }
            failed += preview.filter { it.status != "可添加" && it.status != "已存在" && it.status != "本次重复" }
                .map { "${it.original}：${it.status}" }
            _state.update { it.copy(batchBusy = false, batchResult = BatchImportResult(added, skipped, failed), batchPreview = emptyList()) }
        }
    }

    fun setDisplayName(value: String) = _state.update { it.copy(displayName = value, error = null) }
    fun setMediaType(value: String) = _state.update { it.copy(mediaType = value) }
    fun setOutputFormat(value: String) = _state.update { it.copy(outputFormat = value) }
    fun setQuality(value: String) = _state.update { it.copy(quality = value) }
    fun setFlvDirectDownload(value: Boolean) = _state.update { it.copy(flvDirectDownload = value) }
    fun setStorageDirectoryUri(value: String?) = _state.update { it.copy(storageDirectoryUri = value) }
    fun setSegmentEnabled(value: Boolean) = _state.update { it.copy(segmentEnabled = value) }
    fun setSegmentMinutes(value: String) = _state.update { it.copy(segmentMinutes = value.filter(Char::isDigit).take(5)) }
    fun setScheduledMonitoringEnabled(value: Boolean) = _state.update { it.copy(scheduledMonitoringEnabled = value) }
    fun setNotificationsEnabled(value: Boolean) = _state.update { it.copy(notificationsEnabled = value) }
    fun setCheckIntervalSeconds(value: String) = _state.update {
        it.copy(checkIntervalSeconds = value.filter(Char::isDigit).take(4))
    }

    fun applyTemplate(template: RecordingTemplate) = _state.update {
        it.copy(outputFormat = template.format, quality = template.quality, segmentMinutes = (template.segmentSeconds / 60).toString(), checkIntervalSeconds = template.intervalSeconds.toString())
    }

    fun save() {
        val snapshot = _state.value
        if (snapshot.url.isBlank() || snapshot.isSaving) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            runCatching {
                repository.addRoom(
                    url = snapshot.url,
                    displayName = snapshot.displayName,
                    options = RoomRecordingOptions(
                        mediaType = snapshot.mediaType,
                        outputFormat = snapshot.outputFormat,
                        quality = snapshot.quality,
                        flvDirectDownload = snapshot.flvDirectDownload,
                        storageDirectoryUri = snapshot.storageDirectoryUri,
                        segmentEnabled = snapshot.segmentEnabled,
                        segmentSeconds = ((snapshot.segmentMinutes.toIntOrNull() ?: 30) * 60)
                            .coerceIn(MIN_SEGMENT_SECONDS, MAX_SEGMENT_SECONDS),
                        scheduledMonitoringEnabled = snapshot.scheduledMonitoringEnabled,
                        notificationsEnabled = snapshot.notificationsEnabled,
                        checkIntervalSeconds = snapshot.checkIntervalSeconds.toIntOrNull()?.coerceIn(15, 3_600) ?: 60,
                    ),
                )
            }.onSuccess { id ->
                _state.update { it.copy(isSaving = false, savedRoomId = id) }
            }.onFailure { error ->
                repository.log(
                    roomId = null,
                    sessionId = null,
                    level = "ERROR",
                    eventType = "ROOM_ADD_FAILED",
                    message = error.message ?: "无法添加直播间",
                )
                _state.update {
                    it.copy(isSaving = false, error = error.message ?: "无法添加这个直播间")
                }
            }
        }
    }
}
