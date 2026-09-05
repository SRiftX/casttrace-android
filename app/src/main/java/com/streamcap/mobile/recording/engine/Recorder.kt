package com.streamcap.mobile.recording.engine

import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.domain.model.StreamSource
import kotlinx.coroutines.flow.Flow
import java.io.File

data class RecordingRequest(
    val url: String,
    val headers: Map<String, String>,
    val streamType: StreamType,
    val destination: File,
    val segmentSeconds: Int? = null,
    val refreshSource: (suspend () -> StreamSource?)? = null,
)

data class RecordingHandle(val id: String)

enum class RecorderState {
    STARTING,
    RECORDING,
    COMPLETED,
    STOPPED,
    FAILED,
}

data class RecordingProgress(
    val state: RecorderState,
    val bytesWritten: Long = 0,
    val durationSeconds: Long = 0,
    val errorMessage: String? = null,
    val outputFiles: List<File> = emptyList(),
)

enum class StopReason {
    USER,
    PAUSE,
    TIMER,
    STORAGE_LOW,
    SERVICE_DESTROYED,
}

interface Recorder {
    suspend fun start(request: RecordingRequest): RecordingHandle

    suspend fun stop(handle: RecordingHandle, reason: StopReason)

    fun observe(handle: RecordingHandle): Flow<RecordingProgress>
}
