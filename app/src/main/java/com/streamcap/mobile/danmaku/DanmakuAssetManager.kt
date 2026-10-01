package com.streamcap.mobile.danmaku

import android.content.Context
import com.streamcap.mobile.data.repository.StreamCapRepository
import com.streamcap.mobile.domain.model.TaskStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        DanmakuEventStore.recover(File(path)).filter { it.sessionId == sessionId }
    }
}
