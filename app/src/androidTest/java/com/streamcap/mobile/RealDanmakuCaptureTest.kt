package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Explicit live-room arguments; passing requires real chat and durable local JSONL. */
class RealDanmakuCaptureTest {
    @Test fun capturesRealChatToLocalFile() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit live room arguments", args.containsKey("platform") && args.containsKey("roomUrl"))
        val platform = requireNotNull(args.getString("platform"))
        val url = requireNotNull(args.getString("roomUrl"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(app.filesDir, "danmaku/qa-$platform-${System.currentTimeMillis()}/events.jsonl")
        val client = RealDanmakuClientFactory(OkHttpClient()).forPlatform(platform)
        val statuses = mutableListOf<String>()
        var received = 0
        val store = DanmakuEventStore(file)
        try {
            withTimeout(90_000) {
                client.connect(DanmakuConnectContext(9001, platform, url, args.getString("cookie"))).collect { frame ->
                    when (frame) {
                        is DanmakuFrame.Status -> { statuses += frame.state; println("REAL_CHAT_STATUS ${frame.state}") }
                        is DanmakuFrame.Event -> {
                            assertEquals(platform, frame.event.platform)
                            assertFalse(frame.event.parserVersion.contains("simulat"))
                            assertTrue(frame.event.text.isNotBlank())
                            assertFalse(frame.event.userName.isNullOrBlank())
                            assertTrue(store.append(frame.event))
                            received++
                            if (received >= 3) throw CaptureComplete()
                        }
                    }
                }
            }
        } catch (_: CaptureComplete) {
            // Completion only after actual server messages.
        } finally {
            client.close("qa-finished")
            withContext(Dispatchers.IO) { store.close() }
        }
        assertTrue(statuses.contains("connected-real"))
        val rows = file.readLines().filter(String::isNotBlank).map(::JSONObject)
        assertTrue(rows.size >= 3)
        assertEquals(received.toLong(), store.stats().written)
        assertEquals(0L, store.stats().dropped)
        println("REAL_CHAT_SAVED platform=$platform count=${rows.size} bytes=${file.length()} path=${file.absolutePath}")
    }
    private class CaptureComplete : RuntimeException()
}
