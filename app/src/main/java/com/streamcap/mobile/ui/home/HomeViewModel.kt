package com.streamcap.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.RecordingSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: StreamCapRepository) : ViewModel() {
    val rooms: StateFlow<List<LiveRoom>> = repository.rooms.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val recordings: StateFlow<List<RecordingSession>> = repository.recordings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    fun setEnabled(roomId: Long, enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setEnabled(roomId, enabled)
            afterChange()
        }
    }

    fun refresh(roomId: Long, afterRefresh: (LiveStatus) -> Unit = {}) {
        viewModelScope.launch { afterRefresh(repository.refresh(roomId)) }
    }

    fun setScheduledMonitoringEnabled(roomId: Long, enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setScheduledMonitoringEnabled(roomId, enabled)
            afterChange()
        }
    }
}
