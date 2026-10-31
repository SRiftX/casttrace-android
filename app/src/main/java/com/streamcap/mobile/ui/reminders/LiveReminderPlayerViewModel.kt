package com.streamcap.mobile.ui.reminders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.repository.StreamCapRepository
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
)

@HiltViewModel
class LiveReminderPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: StreamCapRepository,
    private val networkPolicy: RecordingNetworkPolicy,
    private val httpClient: OkHttpClient,
) : ViewModel() {
    private val reminderId = checkNotNull(savedStateHandle.get<Long>("reminderId"))
    private val _state = MutableStateFlow(LiveReminderPlayerState())
    val state: StateFlow<LiveReminderPlayerState> = _state.asStateFlow()

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

    fun requireNetworkAllowed() {
        kotlinx.coroutines.runBlocking { networkPolicy.requireAllowed() }
    }
}
