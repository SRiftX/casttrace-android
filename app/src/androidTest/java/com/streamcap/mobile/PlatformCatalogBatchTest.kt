package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.platform.AndroidSupportStatus
import com.streamcap.mobile.platform.PlatformCatalog
import com.streamcap.mobile.platform.resolver.UnsupportedPlatformException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audits every catalog entry in batches of ten without claiming live media works. */
@dagger.hilt.android.testing.HiltAndroidTest
class PlatformCatalogBatchTest : HiltGraphTest() {
    @Test fun everyCatalogEntryHasAnExplicitResolverState() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val registry = app.testGraph.resolverRegistry
        val entries = PlatformCatalog.entries
        val registered = entries.filter { it.status != AndroidSupportStatus.DISCONTINUED }.map { it.key }.toSet()
        assertEquals(entries.size, entries.map { it.key }.distinct().size)
        entries.chunked(10).forEachIndexed { batchIndex, batch ->
            batch.forEach { entry ->
                val url = if (entry.key == "custom") "https://example.com/live.m3u8"
                    else "https://${entry.domains.first()}/live/123"
                val resolver = runCatching { registry.resolverFor(url) }
                if (entry.key in registered) {
                    assertTrue("${entry.key} resolver is missing", resolver.isSuccess)
                    assertEquals(if (entry.key == "custom") "direct" else entry.key,
                        resolver.getOrThrow().platformId)
                } else {
                    assertTrue("${entry.key} unexpectedly resolved without review",
                        resolver.exceptionOrNull() is UnsupportedPlatformException)
                    assertEquals(AndroidSupportStatus.DISCONTINUED, entry.status)
                }
                android.util.Log.i("PlatformCatalogBatch", "batch=${batchIndex + 1} key=${entry.key} catalog=${entry.status} resolver=${resolver.getOrNull()?.platformId ?: "none"}")
            }
        }
    }
}
