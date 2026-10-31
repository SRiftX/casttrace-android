package com.streamcap.mobile.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.UserSettings
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.storage.RecordingStorage
import com.streamcap.mobile.domain.model.LiveRoom
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.data.database.TaskLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.streamcap.mobile.service.RoomMonitorWorker
import com.streamcap.mobile.data.preferences.RecordingTemplate
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
    private val recordingStorage: RecordingStorage,
) : ViewModel() {
    private val context = context.applicationContext
    val settings: StateFlow<UserSettings> = preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )
    private val logLimit = MutableStateFlow(500)
    private val logLevel = MutableStateFlow<String?>(null)
    private val logRoomId = MutableStateFlow<Long?>(null)
    private val keyLogsOnly = MutableStateFlow(true)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val logs: StateFlow<List<TaskLogEntity>> = combine(logLimit, logLevel, logRoomId, keyLogsOnly) { limit, level, roomId, keyOnly ->
        LogFilter(limit, level, roomId, keyOnly)
    }.flatMapLatest { repository.observeLogs(it.limit, it.level, it.roomId, it.keyOnly) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val logCount: StateFlow<Int> = combine(logLevel, logRoomId, keyLogsOnly) { level, roomId, keyOnly -> Triple(level, roomId, keyOnly) }
        .flatMapLatest { (level, roomId, keyOnly) -> repository.observeLogCount(level, roomId, keyOnly) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val recordings: StateFlow<List<RecordingSession>> = repository.recordings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val rooms: StateFlow<List<LiveRoom>> = repository.rooms.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val recordingFreeBytes: Long get() = recordingStorage.recordingRoot().usableSpace

    fun setLogLevel(level: String?) { logLevel.value = level; logLimit.value = 500 }
    fun setLogRoom(roomId: Long?) { logRoomId.value = roomId; logLimit.value = 500 }
    fun setKeyLogsOnly(value: Boolean) { keyLogsOnly.value = value; logLimit.value = 500 }
    fun loadMoreLogs() { logLimit.value = (logLimit.value + 500).coerceAtMost(100_000) }
    suspend fun logsForExport(): List<TaskLogEntity> = repository.logsForExport()

    private data class LogFilter(val limit: Int, val level: String?, val roomId: Long?, val keyOnly: Boolean)
    val templates = preferences.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveTemplate(template: RecordingTemplate) { viewModelScope.launch { preferences.saveTemplate(template) } }
    fun deleteTemplate(name: String) { viewModelScope.launch { preferences.deleteTemplate(name) } }

    fun setAllowMobileData(value: Boolean) {
        viewModelScope.launch {
            preferences.setAllowMobileData(value)
            RoomMonitorWorker.schedule(context, preferences, repository)
        }
    }

    fun setCheckIntervalSeconds(value: Int) {
        viewModelScope.launch { preferences.setCheckIntervalSeconds(value) }
    }

    fun setDefaultQuality(value: String) {
        viewModelScope.launch { preferences.setDefaultQuality(value) }
    }

    fun setDefaultFormat(value: String) {
        viewModelScope.launch { preferences.setDefaultFormat(value) }
    }

    fun setStorageDirectoryUri(value: String?) {
        viewModelScope.launch { preferences.setStorageDirectoryUri(value) }
    }

    fun setResumeAfterBoot(value: Boolean) {
        viewModelScope.launch {
            preferences.setResumeAfterBoot(value)
            RoomMonitorWorker.schedule(context, preferences, repository)
        }
    }

    fun setGlobalAutoRecordEnabled(value: Boolean) {
        viewModelScope.launch {
            preferences.setGlobalAutoRecordEnabled(value)
            RoomMonitorWorker.schedule(context, preferences, repository)
            com.streamcap.mobile.service.RecordingService.monitor(context)
        }
    }

    fun setBackgroundMonitoringEnabled(value: Boolean) {
        viewModelScope.launch {
            preferences.setBackgroundMonitoringEnabled(value)
            RoomMonitorWorker.schedule(context, preferences, repository)
            com.streamcap.mobile.service.RecordingService.monitor(context)
        }
    }

    fun setPowerSavingMonitoring(value: Boolean) {
        viewModelScope.launch { preferences.setPowerSavingMonitoring(value) }
    }

    fun setLanguage(value: String) {
        viewModelScope.launch { preferences.setLanguage(value) }
    }

    fun setThemePalette(value: String) {
        viewModelScope.launch { preferences.setThemePalette(value) }
    }

    fun setIncludeTitleInFileName(value: Boolean) {
        viewModelScope.launch { preferences.setIncludeTitleInFileName(value) }
    }

    fun setFileNameTemplate(value: String) {
        viewModelScope.launch { preferences.setFileNameTemplate(value) }
    }

    fun setRemoveEmojiFromFileName(value: Boolean) {
        viewModelScope.launch { preferences.setRemoveEmojiFromFileName(value) }
    }
}
