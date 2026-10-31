package com.streamcap.mobile.service

import com.streamcap.mobile.danmaku.DanmakuController
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.recording.engine.Recorder
import com.streamcap.mobile.recording.engine.RecordingHandle
import com.streamcap.mobile.recording.engine.StopReason
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

internal data class ActiveRecording(
    val roomId: Long,
    val roomName: String,
    val platform: String,
    val roomTitle: String?,
    val sessionId: Long,
    val handle: RecordingHandle,
    val recorder: Recorder,
    val temporaryFile: File,
    val streamType: StreamType,
    val outputFormat: String,
    val storageDirectoryUri: String?,
    val notificationsEnabled: Boolean,
    val stopAtMillis: Long?,
    val fileNameTemplate: String,
    val includeTitleInFileName: Boolean,
    val removeEmojiFromFileName: Boolean,
    val danmakuController: DanmakuController? = null,
    val checkpointedFiles: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    @Volatile var stopReason: StopReason? = null,
    var timerJob: Job? = null,
)

/** Mutable coordination shared by the service's monitoring and recording workflows. */
@ServiceScoped
class RecordingRuntimeState @Inject constructor() {
    internal val recoveryMutex = Mutex()
    internal val monitoringPreparationMutex = Mutex()
    internal val active = ConcurrentHashMap<Long, ActiveRecording>()
    internal val starting = ConcurrentHashMap.newKeySet<Long>()
    internal val stopRequested = ConcurrentHashMap.newKeySet<Long>()
    internal val pauseRequested = ConcurrentHashMap.newKeySet<Long>()
    internal val resumeRequested = ConcurrentHashMap.newKeySet<Long>()
    internal val retryCounts = ConcurrentHashMap<Long, Int>()
    internal val sourceAttempts = ConcurrentHashMap<Long, Int>()
    @Volatile internal var monitorJob: Job? = null
    @Volatile internal var powerSavingMonitoring = false
    @Volatile internal var monitoringActive = false
    @Volatile private var monitorGeneration = 0L

    @Synchronized
    internal fun beginMonitorGeneration(): Long = ++monitorGeneration

    @Synchronized
    internal fun attachMonitorJob(generation: Long, job: Job): Boolean {
        if (monitorGeneration != generation) return false
        monitorJob = job
        return true
    }

    @Synchronized
    internal fun activateMonitorGeneration(generation: Long): Boolean {
        if (monitorGeneration != generation) return false
        monitoringActive = true
        return true
    }

    @Synchronized
    internal fun finishMonitorGeneration(generation: Long): Boolean {
        if (monitorGeneration != generation) return false
        monitoringActive = false
        monitorJob = null
        return true
    }

    internal fun isMonitorGenerationCurrent(generation: Long): Boolean = monitorGeneration == generation

    @Synchronized
    internal fun invalidateMonitorGeneration(): Job? {
        monitorGeneration++
        monitoringActive = false
        return monitorJob.also { monitorJob = null }
    }
}
