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
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedRoomId: Long? = null,
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
                )
            }
        }
    }

    fun setInitialUrl(url: String?) {
        if (!url.isNullOrBlank() && _state.value.url.isBlank()) {
            _state.update { it.copy(url = url.trim()) }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value, error = null) }

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
