package com.streamcap.mobile.danmaku

import android.content.Context
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

class DanmakuAssetManager(private val context: Context, private val repository: StreamCapRepository) {
    suspend fun createSimulation(sessionId: Long): String = withContext(Dispatchers.IO) {
        val session = requireNotNull(repository.session(sessionId)) { "录制记录不存在" }
        check(session.status !in setOf(TaskStatus.STARTING, TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.FINALIZING)) { "请在录制结束后生成测试弹幕" }
        check(session.outputUri != null || session.outputUris.isNotEmpty()) { "没有可播放视频" }
        check(session.danmakuPath == null) { "此录制已有弹幕资产，不会覆盖" }
        val file = File(context.filesDir, "danmaku/session-$sessionId/events.jsonl")
        val duration = (session.durationSeconds * 1_000).coerceAtLeast(6_000)
        val times = listOf(1_000L, duration / 2, (duration - 2_000).coerceAtLeast(3_000))
        val client = FakeDanmakuClient(listOf("模拟弹幕：开始", "这是一条中文测试消息", "模拟弹幕：结束"))
        val frames = client.connect(DanmakuConnectContext(sessionId, "simulation", "local"))
        var index = 0
        DanmakuEventStore(file).use { store ->
            frames.collect { frame ->
                if (frame is DanmakuFrame.Event) {
                    store.append(frame.event.copy(mediaTimeMs = times[index], timeQuality = "simulation", parserVersion = "simulation-1"))
                    index++
                }
            }
        }
        val events = DanmakuEventStore.recover(file)
        check(events.size == 3) { "测试弹幕保存不完整" }
        repository.updateSession(session.copy(danmakuPath = file.absolutePath, danmakuEventCount = events.size.toLong()))
        file.absolutePath
    }

    suspend fun events(sessionId: Long): List<DanmakuEvent> = withContext(Dispatchers.IO) {
        val path = repository.session(sessionId)?.danmakuPath ?: return@withContext emptyList()
        val file = File(path)
        val job = currentCoroutineContext()
        val events = mutableListOf<DanmakuEvent>()
        var offset = 0L
        do {
            val page = DanmakuEventStore.readPage(file, sessionId, offset, 500) { !job.isActive }
            events += page.events
            offset = page.nextOffset ?: break
        } while (true)
        events
    }

    suspend fun eventsWindow(sessionId: Long, fromMediaMs: Long, untilMediaMs: Long, maxEvents: Int = 12_000,
                             filter: DanmakuFilter = DanmakuFilter()): List<DanmakuEvent> = withContext(Dispatchers.IO) {
        val path = repository.session(sessionId)?.danmakuPath ?: return@withContext emptyList()
        val job = currentCoroutineContext()
        val file = File(path)
        val manifest = File(file.parentFile, "segments/manifest.json")
        if (manifest.isFile) {
            val entries = runCatching { JSONObject(manifest.readText()).getJSONArray("segments") }.getOrNull()
            if (entries != null) {
                val result = ArrayList<DanmakuEvent>()
                for (index in 0 until entries.length()) {
                    job.ensureActive()
                    val entry = entries.getJSONObject(index)
                    if (entry.getLong("endPlayableMs") <= fromMediaMs || entry.getLong("startPlayableMs") >= untilMediaMs) continue
                    val name = entry.getString("file")
                    if (!Regex("part-\\d{4}\\.jsonl").matches(name)) continue
                    result += DanmakuEventStore.readWindow(File(manifest.parentFile, name), fromMediaMs, untilMediaMs,
                        maxEvents - result.size, filter) { !job.isActive }.filter { it.sessionId == sessionId }
                    if (result.size >= maxEvents) break
                }
                return@withContext result.sortedBy { it.mediaTimeMs }
            }
        }
        DanmakuEventStore.readWindow(file, fromMediaMs, untilMediaMs, maxEvents, filter) { !job.isActive }
            .filter { it.sessionId == sessionId }
    }


    suspend fun eventsPage(sessionId: Long, offset: Long = 0L, limit: Int = 100,
                           filter: DanmakuFilter = DanmakuFilter()): DanmakuEventPage = withContext(Dispatchers.IO) {
        val path = repository.session(sessionId)?.danmakuPath
            ?: return@withContext DanmakuEventPage(emptyList(), null, 0L, 0L)
        val job = currentCoroutineContext()
        DanmakuEventStore.readPage(File(path), sessionId, offset, limit, filter) { !job.isActive }
            .also { job.ensureActive() }
    }

    suspend fun coverage(sessionId: Long): DanmakuCoverage = withContext(Dispatchers.IO) {
        val path = repository.session(sessionId)?.danmakuPath
            ?: return@withContext DanmakuCoverage(null, emptyMap())
        val coverage = File(File(path).parentFile, "coverage.json")
        runCatching {
            val data = JSONObject(coverage.readText())
            val types = data.optJSONObject("eventTypes")
            DanmakuCoverage(data.optLong("droppedCount").takeIf { data.has("droppedCount") },
                types?.let { values -> DanmakuEventType.entries.associateWith { type -> values.optLong(type.name) } }
                    ?: emptyMap())
        }.getOrElse { DanmakuCoverage(null, emptyMap()) }
    }

    suspend fun countMatching(sessionId: Long, filter: DanmakuFilter): Long = withContext(Dispatchers.IO) {
        val path = repository.session(sessionId)?.danmakuPath ?: return@withContext 0L
        val job = currentCoroutineContext()
        DanmakuEventStore.countMatching(File(path), sessionId, filter) { !job.isActive }
    }
}
