package com.streamcap.mobile.ui.reminders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.DanmakuAppearance
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
import com.streamcap.mobile.recording.hls.HlsSourceSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    val quality: String = "原画",
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
    private var resolveJob: Job? = null
    private var activeDanmakuClient: com.streamcap.mobile.danmaku.DanmakuClient? = null
    private var danmakuGeneration = 0L
    private val failedSourceIndexes = mutableSetOf<Int>()

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
        val quality = _state.value.quality
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null, sources = emptyList())
            try {
                val resolved = withContext(Dispatchers.IO) {
                    networkPolicy.requireAllowed()
                    repository.streams(reminder, quality)
                }
                val playable = resolved.distinctBy(StreamSource::url)
                if (playable.isEmpty()) {
                    error("主播目前没有可用直播流")
                }
                val hls = playable.filter { it.type == StreamType.HLS }
                var playbackSources = playable
                var selectedIndex = fallbackPreviewSourceIndex(playable)
                if (hls.isNotEmpty()) {
                    try {
                        val selection = withContext(Dispatchers.IO) {
                            HlsSourceSelector(httpClient).selectWithIndex(hls, quality)
                        }
                        val resolved = applyPreferredHlsSelection(playable, hls, selection)
                        playbackSources = resolved.first
                        selectedIndex = resolved.second
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep raw HLS candidates when preflight is inconclusive so ExoPlayer
                        // can try live playlist variants the lightweight probe does not parse.
                        selectedIndex = fallbackPreviewSourceIndex(playable)
                    }
                }
                _state.value = _state.value.copy(
                    sources = playbackSources,
                    sourceIndex = selectedIndex,
                    loading = false,
                    errorMessage = null,
                )
                failedSourceIndexes.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = _state.value.copy(errorMessage = failure.message ?: "直播线路解析失败", loading = false)
            }
        }
    }

    fun nextSource() {
        val sources = _state.value.sources
        if (sources.size > 1) {
            failedSourceIndexes.clear()
            _state.value = _state.value.copy(sourceIndex = (_state.value.sourceIndex + 1) % sources.size, errorMessage = null)
        }
    }

    fun selectSource(index: Int) {
        if (index in _state.value.sources.indices) {
            failedSourceIndexes.clear()
            _state.value = _state.value.copy(sourceIndex = index, errorMessage = null)
        }
    }

    fun selectQuality(quality: String) {
        if (quality.isBlank() || quality == _state.value.quality) return
        failedSourceIndexes.clear()
        _state.value = _state.value.copy(quality = quality, errorMessage = null)
    }

    fun setDanmakuAppearance(value: DanmakuAppearance) {
        viewModelScope.launch { preferences.setDanmakuAppearance(value) }
    }

    fun onSourcePlaybackFailed(index: Int): Boolean {
        val sources = _state.value.sources
        if (index !in sources.indices) return false
        failedSourceIndexes += index
        val next = nextPreviewSourceIndex(sources.size, failedSourceIndexes, index)
        if (next == null) {
            _state.value = _state.value.copy(errorMessage = "此画质下的线路均无法播放，请降低画质或重新解析")
            return false
        }
        _state.value = _state.value.copy(sourceIndex = next, errorMessage = null)
        return true
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
            val context = DanmakuConnectContext(
                sessionId = reminder.id,
                platform = reminder.platform,
                roomUrl = reminder.normalizedUrl.ifBlank { reminder.originalUrl },
                cookie = credentialStore.get(reminder.platform)?.cookie,
            )
            var attempt = 0
            while (generation == danmakuGeneration && isActive) {
                try {
                    withContext(Dispatchers.IO) { networkPolicy.requireAllowed() }
                    if (attempt > 0) _state.value = _state.value.copy(danmakuStatus = "正在重连弹幕")
                    client.connect(context).collect { frame ->
                        if (generation != danmakuGeneration) return@collect
                        when (frame) {
                            is DanmakuFrame.Status -> {
                                _state.value = _state.value.copy(danmakuStatus = frame.state.displayStatus())
                            }
                            is DanmakuFrame.Event -> {
                                attempt = 0
                                val events = (_state.value.liveDanmaku + frame.event).takeLast(120)
                                _state.value = _state.value.copy(liveDanmaku = events, danmakuStatus = "正在接收弹幕")
                            }
                        }
                    }
                    if (generation != danmakuGeneration) break
                    error("弹幕连接已断开")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (generation != danmakuGeneration) break
                    val waitMillis = (1_000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
                    val reason = failure.message?.takeIf(String::isNotBlank)?.take(80)
                    _state.value = _state.value.copy(
                        danmakuStatus = buildString {
                            append("弹幕连接中断，${waitMillis / 1_000L} 秒后重试")
                            if (reason != null) append("：$reason")
                        },
                    )
                    attempt++
                    delay(waitMillis)
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

internal fun applyPreferredHlsSelection(
    sources: List<StreamSource>,
    hlsSources: List<StreamSource>,
    selection: HlsSourceSelection,
): Pair<List<StreamSource>, Int> {
    val original = hlsSources.getOrNull(selection.sourceIndex) ?: return sources to 0
    val index = sources.indexOfFirst { it.url == original.url }
    if (index < 0) return sources to 0
    return sources.toMutableList().apply { set(index, selection.source) } to index
}

internal fun fallbackPreviewSourceIndex(sources: List<StreamSource>): Int =
    sources.indexOfFirst { it.type != StreamType.HLS }.takeIf { it >= 0 } ?: 0

private fun String.displayStatus(): String = when {
    startsWith("unsupported-real-platform") -> "此平台暂不支持实时弹幕"
    startsWith("error-real") || startsWith("web-chat-page-error") -> "弹幕连接异常"
    startsWith("reconnecting") -> "弹幕重连中"
    startsWith("receiving-real") -> "正在接收弹幕"
    startsWith("subscribed-real") || startsWith("connected-real") -> "已连接，等待弹幕"
    startsWith("connecting") -> "正在连接弹幕"
    startsWith("parse-error") -> "弹幕消息解析异常，正在继续接收"
    startsWith("protocol-text-frame") -> "收到平台文本帧，等待弹幕消息"
    startsWith("observing-web-chat") -> "正在监听页面聊天"
    startsWith("connecting-web-chat") -> "正在连接页面聊天"
    startsWith("closing:") -> "平台正在关闭弹幕连接"
    else -> "等待弹幕"
}
