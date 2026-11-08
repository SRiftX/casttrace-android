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
import com.streamcap.mobile.BuildConfig
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val preferences: AppPreferences,
    private val repository: StreamCapRepository,
    private val recordingStorage: RecordingStorage,
    private val appUpdateManager: AppUpdateManager,
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
    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState = _updateState.asStateFlow()

    fun checkForUpdates() {
        if (_updateState.value.isBusy) return
        _updateState.value = UpdateUiState(message = "正在检查更新…", isBusy = true)
        viewModelScope.launch {
            runCatching { appUpdateManager.checkLatest(BuildConfig.VERSION_NAME) }
                .onSuccess { update ->
                    _updateState.value = if (update == null) {
                        UpdateUiState(message = "当前已是最新版本")
                    } else {
                        UpdateUiState(message = "发现新版本 ${update.versionName}", update = update)
                    }
                }
                .onFailure { error ->
                    _updateState.value = UpdateUiState(message = error.message ?: "检查更新失败")
                }
        }
    }

    fun downloadAndInstallUpdate() {
        val update = _updateState.value.update ?: return
        if (_updateState.value.isBusy) return
        _updateState.value = _updateState.value.copy(message = "正在下载 ${update.versionName}…", isBusy = true)
        viewModelScope.launch {
            runCatching { appUpdateManager.download(update) }
                .onSuccess { apk ->
                val prompt = runCatching { appUpdateManager.launchInstaller(apk) }.getOrNull()
                    _updateState.value = UpdateUiState(
                        message = when (prompt) {
                            InstallPrompt.OPENED -> "下载完成，正在打开系统安装程序"
                            InstallPrompt.PERMISSION_REQUIRED -> "请允许安装未知应用，然后返回并点按安装更新"
                            null -> "下载完成，点按安装更新继续"
                        },
                        update = update,
                        downloaded = true,
                    )
                }
                .onFailure { error ->
                    _updateState.value = UpdateUiState(message = error.message ?: "下载更新失败", update = update)
                }
        }
    }

    fun installDownloadedUpdate() {
        val update = _updateState.value.update ?: return
        if (!_updateState.value.downloaded || _updateState.value.isBusy) return
        _updateState.value = _updateState.value.copy(message = "正在打开安装程序…", isBusy = true)
        viewModelScope.launch {
            val result = runCatching {
                val apk = appUpdateManager.findCached(update) ?: appUpdateManager.download(update)
                appUpdateManager.launchInstaller(apk)
            }
            _updateState.value = when (result.getOrNull()) {
                InstallPrompt.OPENED -> _updateState.value.copy(message = "正在打开系统安装程序", isBusy = false)
                InstallPrompt.PERMISSION_REQUIRED -> _updateState.value.copy(message = "请允许安装未知应用，然后返回并点按安装更新", isBusy = false)
                null -> _updateState.value.copy(message = result.exceptionOrNull()?.message ?: "无法打开安装程序", isBusy = false)
            }
        }
    }

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
