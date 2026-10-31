package com.streamcap.mobile

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.platform.AndroidSupportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** Checks catalog host reachability in concurrent groups; a root page is not a live-room test. */
@dagger.hilt.android.testing.HiltAndroidTest
class PlatformNetworkBatchTest : HiltGraphTest() {
    @Test fun probeRemainingCatalogHostsTenAtATime() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val dedicated = setOf("custom", "huya", "douyin", "bilibili", "douyu", "kuaishou",
            "maoer", "showroom", "chzzk", "picarto", "twitcasting", "langlive", "yiqilive")
        PlatformCatalog.entries.filterNot { it.key in dedicated || it.status == AndroidSupportStatus.DISCONTINUED }
            .chunked(10).forEachIndexed { batch, entries ->
            entries.map { entry ->
                async(Dispatchers.IO) {
                    val url = "https://${entry.domains.first()}/"
                    val result = runCatching {
                        withTimeout(20_000) { app.testGraph.resolverRegistry.resolverFor(url).resolveRoom(url) }
                    }
                    Log.i("PlatformNetworkBatch", "batch=${batch + 1} key=${entry.key} host=${entry.domains.first()} " +
                        "page=${if (result.isSuccess) "OK" else "FAILED"} reason=${result.exceptionOrNull()?.message.orEmpty()}")
                }
            }.awaitAll()
        }
    }
}
