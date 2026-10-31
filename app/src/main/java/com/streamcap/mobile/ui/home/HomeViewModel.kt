package com.streamcap.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.UserSettings
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.LiveStatus
import com.streamcap.mobile.domain.model.RecordingSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: StreamCapRepository,
    private val preferences: AppPreferences,
) : ViewModel() {
    val settings: StateFlow<UserSettings> = preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )
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

    fun latestErrorForRoom(roomId: Long) = repository.observeLatestErrorForRoom(roomId)

    fun setEnabled(roomId: Long, enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setEnabled(roomId, enabled)
            afterChange()
        }
    }

    fun refresh(roomId: Long, afterRefresh: (LiveStatus) -> Unit = {}) {
        viewModelScope.launch { afterRefresh(repository.refresh(roomId)) }
    }

    fun refreshMonitoredRooms() {
        viewModelScope.launch {
            repository.monitoredRooms().forEach { repository.refresh(it.id) }
        }
    }

    fun setScheduledMonitoringEnabled(roomId: Long, enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            repository.setScheduledMonitoringEnabled(roomId, enabled)
            afterChange()
        }
    }

    fun setGlobalAutoRecordEnabled(enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            preferences.setGlobalAutoRecordEnabled(enabled)
            afterChange()
        }
    }

    fun setBackgroundMonitoringEnabled(enabled: Boolean, afterChange: () -> Unit = {}) {
        viewModelScope.launch {
            preferences.setBackgroundMonitoringEnabled(enabled)
            afterChange()
        }
    }
}
