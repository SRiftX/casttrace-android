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
    /** Structured transport events are persisted by the recording service. */
    val eventLogger: ((String, Map<String, String>) -> Unit)? = null,
    /** True when the previous connection delivered new media. */
    val refreshSource: (suspend (Boolean) -> StreamSource?)? = null,
)

data class RecordingHandle(val id: String)

enum class RecorderState {
    STARTING,
    RECORDING,
    RECONNECTING,
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
    /** Files whose writers have been closed and can be safely published while recording continues. */
    val completedOutputFiles: List<File> = emptyList(),
    val liveEnded: Boolean = false,
    val retryExhausted: Boolean = false,
)

class StreamRetryExhaustedException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class StopReason {
    USER,
    PAUSE,
    TIMER,
    STORAGE_LOW,
    NETWORK_POLICY,
    SERVICE_DESTROYED,
}

interface Recorder {
    suspend fun start(request: RecordingRequest): RecordingHandle

    suspend fun stop(handle: RecordingHandle, reason: StopReason)

    fun observe(handle: RecordingHandle): Flow<RecordingProgress>
}
