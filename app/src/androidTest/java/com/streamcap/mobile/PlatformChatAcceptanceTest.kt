package com.streamcap.mobile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.danmaku.DanmakuConnectContext
import com.streamcap.mobile.danmaku.DanmakuFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.io.File

/** Receive-only opt-in live probes. Never sends public messages or modifies saved rooms. */
@dagger.hilt.android.testing.HiltAndroidTest
class PlatformChatAcceptanceTest : HiltGraphTest() {
    @Test fun receiveRealChat() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val urls = args.getString("chatUrls").orEmpty().split(',').map(String::trim).filter(String::isNotBlank)
        require(urls.isNotEmpty()) { "chatUrls must contain real room URLs" }
        val app = instrumentation.targetContext.applicationContext
        val seconds = args.getString("chatSeconds")?.toLongOrNull()?.coerceIn(15, 180) ?: 60L
        urls.chunked(10).forEach { batch -> batch.map { url -> async(Dispatchers.IO) {
            val platform = app.testGraph.resolverRegistry.resolverFor(url).platformId
            val factory = app.testGraph.danmakuClients
            if (!factory.supports(platform)) {
                Log.i("PlatformChat", "RESULT platform=$platform state=UNSUPPORTED")
                return@async
            }
            val client = factory.forPlatform(platform)
            var count = 0
            val eventTypes = linkedMapOf<String, Int>()
            var subscribed = false
            val statuses = linkedSetOf<String>()
            val failure = runCatching {
                withTimeoutOrNull(seconds * 1000) {
                    client.connect(DanmakuConnectContext(0, platform, url)).collect { frame ->
                        when (frame) {
                            is DanmakuFrame.Status -> { statuses += frame.state; if (frame.state == "subscribed-real") subscribed = true }
                            is DanmakuFrame.Event -> {
                                count++; check(frame.event.platform == platform); check(frame.event.text.isNotBlank())
                                val type = frame.event.eventType.name
                                eventTypes[type] = (eventTypes[type] ?: 0) + 1
                            }
                        }
                    }
                }
            }.exceptionOrNull()
            client.close("qa-finished")
            val state = when { count > 0 -> "CHAT_OK"; failure != null -> "FAILED"; subscribed -> "SUBSCRIBED_NO_MESSAGES"; else -> "NO_MESSAGES" }
            val result = "platform=$platform state=$state count=$count types=${eventTypes.entries.joinToString { "${it.key}:${it.value}" }} statuses=${statuses.joinToString()} error=${failure?.javaClass?.simpleName.orEmpty()}"
            File(instrumentation.targetContext.filesDir, "chat-$platform-result.txt").writeText(result)
            Log.i("PlatformChat", "RESULT $result")
        } }.awaitAll() }
    }
}
