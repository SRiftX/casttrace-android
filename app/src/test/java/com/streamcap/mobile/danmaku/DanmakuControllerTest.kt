package com.streamcap.mobile.danmaku

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class DanmakuControllerTest {
    @Test fun reconnectsDeduplicatesAndDrainsOnStop() = runTest {
        var connects = 0
        val client = object : DanmakuClient {
            override suspend fun connect(context: DanmakuConnectContext) = flow<DanmakuFrame> {
                connects++
                emit(DanmakuFrame.Event(DanmakuEvent(1, "huya", "same", 150, 0, "test", text = "chat")))
                if (connects == 1) error("connection lost")
                emit(DanmakuFrame.Event(DanmakuEvent(1, "huya", "new", 170, 0, "test", text = "chat")))
                awaitCancellation()
            }
            override suspend fun close(reason: String) = Unit
        }
        val file = File(createTempDirectory().toFile(), "events.jsonl")
        val store = DanmakuEventStore(file)
        val controller = DanmakuController(this, client, store)
        controller.start(DanmakuConnectContext(1, "huya", "local", mediaStartedElapsedMs = 100))
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        val stats = controller.stop("test")
        assertEquals(2, connects)
        assertEquals(2L, stats.written)
        assertEquals(listOf(50L, 70L), DanmakuEventStore.recover(file).map { it.mediaTimeMs })
        advanceTimeBy(60_000)
        assertEquals(2, connects)
    }
}
