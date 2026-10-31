package com.streamcap.mobile.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcap.mobile.data.preferences.AppPreferences
import com.streamcap.mobile.data.preferences.DanmakuAppearance
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.danmaku.DanmakuAssetManager
import com.streamcap.mobile.danmaku.DanmakuCoverage
import com.streamcap.mobile.danmaku.DanmakuEvent
import com.streamcap.mobile.danmaku.DanmakuEventPage
import com.streamcap.mobile.danmaku.DanmakuFilter
import com.streamcap.mobile.danmaku.DanmakuSubtitleExporter
import com.streamcap.mobile.danmaku.DanmakuSubtitleFormat
import com.streamcap.mobile.domain.model.RecordingSession
import com.streamcap.mobile.domain.model.StreamSource
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.network.RecordingNetworkPolicy
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import com.streamcap.mobile.storage.RecordingStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

@HiltViewModel
class RecordingPlayerViewModel @Inject constructor(
    private val repository: StreamCapRepository,
    private val storage: RecordingStorage,
    private val preferences: AppPreferences,
    val networkPolicy: RecordingNetworkPolicy,
    private val httpClient: okhttp3.OkHttpClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val danmakuAppearance: StateFlow<DanmakuAppearance> = preferences.danmakuAppearance.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), DanmakuAppearance(),
    )

    fun observeRecording(recordingId: Long) = repository.observeRecording(recordingId).map { session ->
        if (session == null) null else withContext(Dispatchers.IO) {
            val explicit = session.outputUris.ifEmpty { listOfNotNull(session.outputUri) }
            val discovered = runCatching {
                storage.publishedSiblingParts(explicit, session.roomName, session.startedAt)
            }.getOrDefault(explicit)
            if (discovered.size > explicit.size) session.copy(outputUri = discovered.first(), outputUris = discovered) else session
        }
    }

    suspend fun selectLiveSource(recording: RecordingSession, retry: Int): StreamSource? = withContext(Dispatchers.IO) {
        val sources = repository.streams(recording.roomId)
        val hls = sources.filter { it.type == StreamType.HLS }
        val flv = sources.filter { it.type == StreamType.FLV }
        if (recording.streamType == StreamType.FLV && flv.isNotEmpty()) {
            flv[retry.mod(flv.size)]
        } else if (hls.isNotEmpty()) {
            try {
                HlsSourceSelector(httpClient).select(hls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sources.firstOrNull { it.type == StreamType.FLV } ?: throw failure
            }
        } else sources.firstOrNull()
    }

    suspend fun publishedPartDurationsSeconds(uris: List<String>): List<Long> = withContext(Dispatchers.IO) {
        storage.publishedPartDurationsSeconds(uris)
    }

    suspend fun refreshPublishedParts(recordingId: Long): List<String> = withContext(Dispatchers.IO) {
        val session = repository.session(recordingId) ?: return@withContext emptyList()
        storage.publishedSiblingParts(
            session.outputUris.ifEmpty { listOfNotNull(session.outputUri) },
            session.roomName,
            session.startedAt,
        )
    }

    suspend fun eventsWindow(
        sessionId: Long,
        fromMediaMs: Long,
        untilMediaMs: Long,
        filter: DanmakuFilter,
    ): List<DanmakuEvent> = DanmakuAssetManager(context, repository)
        .eventsWindow(sessionId, fromMediaMs, untilMediaMs, filter = filter)

    suspend fun eventsPage(sessionId: Long, offset: Long, filter: DanmakuFilter): DanmakuEventPage =
        DanmakuAssetManager(context, repository).eventsPage(sessionId, offset, 100, filter)

    suspend fun coverage(sessionId: Long): DanmakuCoverage = DanmakuAssetManager(context, repository).coverage(sessionId)

    suspend fun countMatching(sessionId: Long, filter: DanmakuFilter): Long =
        DanmakuAssetManager(context, repository).countMatching(sessionId, filter)

    suspend fun exportSubtitle(recording: RecordingSession, destination: Uri, format: DanmakuSubtitleFormat) =
        withContext(Dispatchers.IO) {
            val path = requireNotNull(recording.danmakuPath)
            val temporary = java.io.File(context.cacheDir, "danmaku-export-${recording.id}.${format.name.lowercase()}")
            val job = coroutineContext[Job]
            try {
                DanmakuSubtitleExporter.exportFile(
                    java.io.File(path), recording.id, temporary, format, preferences.danmakuAppearance.first(),
                ) { job?.isActive != true }
                coroutineContext.ensureActive()
                val output = requireNotNull(context.contentResolver.openOutputStream(destination, "w")) { "导出目录不可写" }
                output.use { stream -> temporary.inputStream().use { it.copyTo(stream) } }
            } finally {
                temporary.delete()
            }
        }

    fun setDanmakuAppearance(value: DanmakuAppearance) {
        viewModelScope.launch { preferences.setDanmakuAppearance(value) }
    }
}
