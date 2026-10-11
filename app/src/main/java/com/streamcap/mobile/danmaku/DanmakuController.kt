package com.streamcap.mobile.danmaku

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

class DanmakuController(
    private val scope: CoroutineScope,
    private val client: DanmakuClient,
    private val store: DanmakuEventStore,
    private val onStatus: (String) -> Unit = {},
) {
    private var job: Job? = null
    private var generation = 0L
    fun start(context: DanmakuConnectContext) {
        val previous = job
        previous?.cancel()
        val current = ++generation
        job = scope.launch {
            previous?.join()
            val recent = LinkedHashSet<String>()
            var attempts = 0
            var lastFailureStatusAt = 0L
            while (currentCoroutineContext().isActive && generation == current) {
                var receivedThisConnection = 0
                val connectionStartedAt = System.nanoTime()
                try {
                    client.connect(context).collect { frame ->
                        if (!currentCoroutineContext().isActive || generation != current) return@collect
                        if (frame is DanmakuFrame.Status) {
                            if (frame.state == "subscribed-real") attempts = 0
                            onStatus(frame.state)
                            return@collect
                        }
                        if (frame !is DanmakuFrame.Event) return@collect
                        val event = frame.event
                        if (event.sessionId != context.sessionId) return@collect
                        val id = event.eventId
                        if (id != null) {
                            if (!recent.add(id)) return@collect
                            if (recent.size > 4_096) recent.remove(recent.first())
                        }
                        val saved = event.copy(
                            mediaTimeMs = context.mediaStartedElapsedMs?.let { (event.receivedElapsedMs - it).coerceAtLeast(0) },
                            timeQuality = if (context.mediaStartedElapsedMs != null) "received-estimate" else event.timeQuality,
                        )
                        if (store.append(saved)) {
                            receivedThisConnection++
                            if (receivedThisConnection == 1) onStatus("receiving-real")
                            if (receivedThisConnection >= 10 || System.nanoTime() - connectionStartedAt >= 30_000_000_000L) attempts = 0
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val now = System.nanoTime()
                    if (now - lastFailureStatusAt >= 30_000_000_000L || lastFailureStatusAt == 0L) {
                        onStatus(if (error is IllegalStateException && error.message == "弹幕文件保存失败")
                            "error-real-storage" else "error-real-connection")
                        lastFailureStatusAt = now
                    }
                    if (error is IllegalStateException && error.message == "弹幕文件保存失败") break
                }
                attempts++
                onStatus("reconnecting:$attempts")
                delay((1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L))
            }
        }
    }
    suspend fun stop(reason: String): DanmakuStoreStats {
        generation++
        runCatching { client.close(reason) }
        job?.cancelAndJoin(); job = null
        store.close()
        return store.stats()
    }
}
