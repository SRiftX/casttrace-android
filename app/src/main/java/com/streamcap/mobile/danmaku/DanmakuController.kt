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
            var receivedFirst = false
            var attempts = 0
            while (currentCoroutineContext().isActive && generation == current) {
                try {
                    client.connect(context).collect { frame ->
                        if (!currentCoroutineContext().isActive || generation != current) return@collect
                        if (frame is DanmakuFrame.Status) {
                            if (frame.state == "connected-real") attempts = 0
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
                        if (store.append(saved) && !receivedFirst) {
                            receivedFirst = true
                            onStatus("receiving-real")
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    onStatus("error-real-connection")
                }
                attempts++
                onStatus("reconnecting:$attempts")
                delay((1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L))
            }
        }
    }
    suspend fun stop(reason: String): DanmakuStoreStats {
        generation++
        job?.cancelAndJoin(); job = null
        runCatching { client.close(reason) }
        store.close()
        return store.stats()
    }
}
