package com.streamcap.mobile.ui.roomdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RoomDetailViewModel(
    private val roomId: Long,
    private val repository: StreamCapRepository,
) : ViewModel() {
    val room: StateFlow<LiveRoom?> = repository.observeRoom(roomId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )

    fun refresh(afterRefresh: (LiveStatus) -> Unit = {}) {
        viewModelScope.launch { afterRefresh(repository.refresh(roomId)) }
    }

    fun setEnabled(enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setEnabled(roomId, enabled)
            afterChange()
        }
    }

    fun setQuality(quality: String) {
        viewModelScope.launch { repository.setQuality(roomId, quality) }
    }

    fun setOutputFormat(format: String) = updateOptions { it.copy(outputFormat = format) }
    fun setSegmentEnabled(enabled: Boolean) = updateOptions { it.copy(segmentEnabled = enabled) }
    fun setNotificationsEnabled(enabled: Boolean) = updateOptions { it.copy(notificationsEnabled = enabled) }
    fun setScheduledMonitoringEnabled(enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setScheduledMonitoringEnabled(roomId, enabled)
            afterChange()
        }
    }

    private fun updateOptions(change: (RoomRecordingOptions) -> RoomRecordingOptions) {
        val current = room.value ?: return
        val options = RoomRecordingOptions(
            mediaType = current.mediaType,
            outputFormat = current.outputFormat,
            quality = current.quality,
            flvDirectDownload = current.flvDirectDownload,
            storageDirectoryUri = current.storageDirectoryUri,
            segmentEnabled = current.segmentEnabled,
            segmentSeconds = current.segmentSeconds,
            scheduledMonitoringEnabled = current.scheduledMonitoringEnabled,
            notificationsEnabled = current.notificationsEnabled,
        )
        viewModelScope.launch { repository.updateRecordingOptions(roomId, change(options)) }
    }

    fun delete(afterDelete: () -> Unit) {
        viewModelScope.launch {
            repository.deleteRoom(roomId)
            afterDelete()
        }
    }
}
