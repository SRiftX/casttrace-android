package com.streamcap.mobile.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.UserSettings
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.database.TaskLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.streamcap.mobile.service.RoomMonitorWorker
import com.streamcap.mobile.data.preferences.RecordingTemplate

class SettingsViewModel(
    context: Context,
    private val preferences: AppPreferences,
    repository: StreamCapRepository,
) : ViewModel() {
    private val context = context.applicationContext
    val settings: StateFlow<UserSettings> = preferences.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UserSettings(),
    )
    val logs: StateFlow<List<TaskLogEntity>> = repository.logs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val templates = preferences.templates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveTemplate(template: RecordingTemplate) { viewModelScope.launch { preferences.saveTemplate(template) } }
    fun deleteTemplate(name: String) { viewModelScope.launch { preferences.deleteTemplate(name) } }

    fun setAllowMobileData(value: Boolean) {
        viewModelScope.launch {
            preferences.setAllowMobileData(value)
            RoomMonitorWorker.schedule(context, preferences)
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
            RoomMonitorWorker.schedule(context, preferences)
        }
    }

    fun setGlobalAutoRecordEnabled(value: Boolean) {
        viewModelScope.launch {
            preferences.setGlobalAutoRecordEnabled(value)
            RoomMonitorWorker.schedule(context, preferences)
            if (value) com.streamcap.mobile.service.RecordingService.monitor(context)
            else com.streamcap.mobile.service.RecordingService.stopMonitoring(context)
        }
    }

    fun setBackgroundMonitoringEnabled(value: Boolean) {
        viewModelScope.launch {
            preferences.setBackgroundMonitoringEnabled(value)
            RoomMonitorWorker.schedule(context, preferences)
            val settings = preferences.settings.first()
            if (settings.globalAutoRecordEnabled && value) com.streamcap.mobile.service.RecordingService.monitor(context)
            else if (!value) com.streamcap.mobile.service.RecordingService.stopMonitoring(context)
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
