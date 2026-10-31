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
    @Test fun paidAndChatIdsDoNotCollideAndRetransmissionsAreDeduplicated() = runTest {
        val file = File(createTempDirectory().toFile(), "events.jsonl")
        val client = object : DanmakuClient {
            override suspend fun connect(context: DanmakuConnectContext) = flow<DanmakuFrame> {
                val base = DanmakuEvent(1, "bilibili", "same", 150, 0, "test", text = "hello", rawType = "DANMU_MSG")
                emit(DanmakuFrame.Event(base))
                val gift = base.copy(rawType = "SEND_GIFT", eventType = DanmakuEventType.GIFT)
                emit(DanmakuFrame.Event(gift)); emit(DanmakuFrame.Event(gift))
                awaitCancellation()
            }
            override suspend fun close(reason: String) = Unit
        }
        val controller = DanmakuController(this, client, DanmakuEventStore(file))
        controller.start(DanmakuConnectContext(1, "bilibili", "local", mediaStartedElapsedMs = 100))
        runCurrent()
        assertEquals(2L, controller.stop("test").written)
        assertEquals(listOf(DanmakuEventType.CHAT, DanmakuEventType.GIFT), DanmakuEventStore.recover(file).map { it.eventType })
        file.parentFile?.deleteRecursively()
    }

    @Test fun openingSocketDoesNotResetReconnectBackoff() = runTest {
        var connects = 0
        val client = object : DanmakuClient {
            override suspend fun connect(context: DanmakuConnectContext) = flow<DanmakuFrame> {
                connects++
                emit(DanmakuFrame.Status("connected-real"))
                error("closed before auth")
            }
            override suspend fun close(reason: String) = Unit
        }
        val controller = DanmakuController(this, client, DanmakuEventStore(File(createTempDirectory().toFile(), "events.jsonl")))
        controller.start(DanmakuConnectContext(1, "test", "local"))
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, connects)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(2, connects)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(3, connects)
        controller.stop("test")
    }

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
