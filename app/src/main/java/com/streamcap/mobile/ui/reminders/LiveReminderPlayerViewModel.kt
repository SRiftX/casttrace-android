package com.streamcap.mobile.ui.reminders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.data.security.CredentialStore
import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuFrame
import com.streamcap.mobile.danmaku.RealDanmakuClientFactory
import com.streamcap.mobile.domain.model.LiveReminder
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

data class LiveReminderPlayerState(
    val reminder: LiveReminder? = null,
    val sources: List<StreamSource> = emptyList(),
    val sourceIndex: Int = 0,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val liveDanmaku: List<DanmakuEvent> = emptyList(),
    val danmakuStatus: String = "等待连接",
)

@HiltViewModel
class LiveReminderPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StreamCapRepository,
    private val networkPolicy: RecordingNetworkPolicy,
    private val httpClient: OkHttpClient,
    private val danmakuClients: RealDanmakuClientFactory,
    private val credentialStore: CredentialStore,
    private val preferences: AppPreferences,
) : ViewModel() {
    private val reminderId = checkNotNull(savedStateHandle.get<Long>("reminderId"))
    private val _state = MutableStateFlow(LiveReminderPlayerState())
    val state: StateFlow<LiveReminderPlayerState> = _state.asStateFlow()
    val danmakuAppearance = preferences.danmakuAppearance
    private var danmakuJob: Job? = null
    private var activeDanmakuClient: com.streamcap.mobile.danmaku.DanmakuClient? = null
    private var danmakuGeneration = 0L

    init {
        viewModelScope.launch {
            repository.reminders.collectLatest { reminders ->
                val reminder = reminders.firstOrNull { it.id == reminderId }
                _state.value = _state.value.copy(
                    reminder = reminder,
                    sources = if (reminder == null) emptyList() else _state.value.sources,
                    loading = if (reminder == null) false else _state.value.loading,
                    errorMessage = if (reminder == null) "这个主播提醒已不存在" else _state.value.errorMessage,
                )
            }
        }
    }

    fun resolveSources() {
        val reminder = _state.value.reminder
        if (reminder == null) {
            _state.value = _state.value.copy(loading = false, errorMessage = "这个主播提醒已不存在", sources = emptyList())
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, sources = emptyList())
            try {
                val resolved = withContext(Dispatchers.IO) {
                    networkPolicy.requireAllowed()
                    repository.streams(reminder)
                }
                val playable = resolved.filterNot(StreamSource::requiresFfmpeg)
                if (playable.isEmpty()) {
                    error(if (resolved.isEmpty()) "主播目前没有可用直播流" else "此直播线路暂不支持应用内播放")
                }
                val hls = playable.filter { it.type == StreamType.HLS }
                val preferred = if (hls.isNotEmpty()) {
                    try {
                        withContext(Dispatchers.IO) { HlsSourceSelector(httpClient).select(hls) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        hls.first()
                    }
                } else playable.first()
                _state.value = _state.value.copy(
                    sources = playable,
                    sourceIndex = playable.indexOf(preferred).coerceAtLeast(0),
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.message ?: "直播线路解析失败", loading = false)
            }
        }
    }

    fun nextSource() {
        val sources = _state.value.sources
        if (sources.size > 1) _state.value = _state.value.copy(sourceIndex = (_state.value.sourceIndex + 1) % sources.size)
    }

    fun selectSource(index: Int) {
        if (index in _state.value.sources.indices) _state.value = _state.value.copy(sourceIndex = index)
    }

    fun startLiveDanmaku() {
        val reminder = _state.value.reminder ?: return
        stopLiveDanmaku()
        val generation = danmakuGeneration
        val client = danmakuClients.forPlatform(reminder.platform)
        activeDanmakuClient = client
        if (!danmakuClients.supports(reminder.platform)) {
            _state.value = _state.value.copy(danmakuStatus = "此平台暂不支持实时弹幕")
            return
        }
        _state.value = _state.value.copy(liveDanmaku = emptyList(), danmakuStatus = "正在连接弹幕")
        danmakuJob = viewModelScope.launch {
            try {
                val context = DanmakuConnectContext(
                    sessionId = reminder.id,
                    platform = reminder.platform,
                    roomUrl = reminder.normalizedUrl,
                    cookie = credentialStore.get(reminder.platform)?.cookie,
                )
                client.connect(context).collectLatest { frame ->
                    if (generation != danmakuGeneration) return@collectLatest
                    when (frame) {
                        is DanmakuFrame.Status -> _state.value = _state.value.copy(danmakuStatus = frame.state.displayStatus())
                        is DanmakuFrame.Event -> {
                            val events = (_state.value.liveDanmaku + frame.event).takeLast(120)
                            _state.value = _state.value.copy(liveDanmaku = events, danmakuStatus = "正在接收弹幕")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == danmakuGeneration) {
                    _state.value = _state.value.copy(danmakuStatus = failure.message ?: "弹幕连接失败")
                }
            }
        }
    }

    fun stopLiveDanmaku() {
        danmakuGeneration++
        danmakuJob?.cancel()
        danmakuJob = null
        activeDanmakuClient?.let { client -> viewModelScope.launch { runCatching { client.close("live-preview-closed") } } }
        activeDanmakuClient = null
    }

    fun requireNetworkAllowed() {
        kotlinx.coroutines.runBlocking { networkPolicy.requireAllowed() }
    }

    override fun onCleared() {
        stopLiveDanmaku()
        super.onCleared()
    }
}

private fun String.displayStatus(): String = when {
    startsWith("unsupported-real-platform") -> "此平台暂不支持实时弹幕"
    startsWith("error-real") || startsWith("web-chat-page-error") -> "弹幕连接异常"
    startsWith("reconnecting") -> "弹幕重连中"
    startsWith("receiving-real") -> "正在接收弹幕"
    startsWith("subscribed-real") || startsWith("connected-real") -> "已连接，等待弹幕"
    startsWith("connecting") -> "正在连接弹幕"
    else -> "等待弹幕"
}
