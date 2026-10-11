package com.streamcap.mobile

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.StreamType
import com.streamcap.mobile.platform.AndroidSupportStatus
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.recording.hls.HlsSourceSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Read-only checks against the installed formal package; no room or recording is created. */
class FormalPlatformBatchTest {
    @Test fun catalogHostsTenAtATime() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val client = app.container.httpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        PlatformCatalog.entries.filter { it.key != "custom" && it.status != AndroidSupportStatus.DISCONTINUED }
            .chunked(10).forEachIndexed { batch, entries ->
                val start = SystemClock.elapsedRealtime()
                entries.map { entry -> async(Dispatchers.IO) {
                    val host = entry.domains.first()
                    val state = runCatching {
                        withTimeout(20_000) {
                            client.newCall(Request.Builder().url("https://$host/").head().build())
                                .execute().use { "HTTP_${it.code}" }
                        }
                    }.getOrElse { "FAILED_${it.javaClass.simpleName}" }
                    Log.i("FormalPlatformBatch", "CATALOG batch=${batch + 1} key=${entry.key} state=$state")
                } }.awaitAll()
                val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                Log.i("FormalPlatformBatch", "BATCH batch=${batch + 1} count=${entries.size} elapsedMs=${SystemClock.elapsedRealtime() - start} pssKb=${memory.totalPss} threads=${Thread.activeCount()} pid=${Process.myPid()}")
            }
    }

    @Test fun savedRoomsResolveAndReadMediaTenAtATime() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        val rooms = app.container.repository.rooms.first()
        assertTrue("No saved rooms in formal package", rooms.isNotEmpty())
        rooms.chunked(10).forEachIndexed { batch, group ->
            group.map { room -> async(Dispatchers.IO) {
                val result = runCatching {
                    withTimeout(60_000) {
                        val sources = app.container.repository.streams(room.id)
                        if (sources.isEmpty()) "NO_SOURCE" else {
                            val hls = sources.filter { it.type == StreamType.HLS }
                            val selected = runCatching { HlsSourceSelector(app.container.httpClient).select(hls) }.getOrNull()
                            if (selected != null) "MEDIA_OK_HLS" else {
                                val flv = sources.firstOrNull { it.type == StreamType.FLV }
                                if (flv == null) "MEDIA_FAILED" else {
                                    val request = Request.Builder().url(flv.url).apply { flv.headers.forEach(::header) }.build()
                                    app.container.httpClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful && response.body?.source()?.exhausted() == false) "MEDIA_OK_FLV" else "MEDIA_FAILED_HTTP_${response.code}"
                                    }
                                }
                            }
                        }
                    }
                }.getOrElse { failure ->
                    val reason = failure.message.orEmpty().substringBefore('?').take(80)
                    "FAILED_${failure.javaClass.simpleName}_$reason"
                }
                Log.i("FormalPlatformBatch", "ROOM batch=${batch + 1} room=${room.id} platform=${room.platform} state=$result")
            } }.awaitAll()
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            Log.i("FormalPlatformBatch", "ROOM_BATCH batch=${batch + 1} count=${group.size} pssKb=${memory.totalPss} threads=${Thread.activeCount()}")
        }
    }
}
