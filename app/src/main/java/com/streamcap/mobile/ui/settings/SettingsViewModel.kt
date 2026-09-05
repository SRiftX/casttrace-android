package com.streamcap.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.UserSettings
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.database.TaskLogEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val preferences: AppPreferences,
    repository: StreamCapRepository,
) : ViewModel() {
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

    fun setAllowMobileData(value: Boolean) {
        viewModelScope.launch { preferences.setAllowMobileData(value) }
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
        viewModelScope.launch { preferences.setResumeAfterBoot(value) }
    }

    fun setLanguage(value: String) {
        viewModelScope.launch { preferences.setLanguage(value) }
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
