package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class SavedDurationTimingTest {
    @Test fun measureExistingPartDurationLookup(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as StreamCapApplication).container
        val sessionId = InstrumentationRegistry.getArguments().getString("sessionId")?.toLongOrNull()
        assumeTrue("Specify a sessionId", sessionId != null)
        val uris = container.repository.session(sessionId!!)?.outputUris.orEmpty()
        assumeTrue("Session must contain saved parts", uris.isNotEmpty())
        uris.forEachIndexed { index, uri ->
            val start = android.os.SystemClock.elapsedRealtime()
            val duration = container.recordingStorage.publishedDurationSeconds(listOf(uri))
            android.util.Log.i("StreamCapSave", "duration part=${index + 1} seconds=$duration elapsed=${android.os.SystemClock.elapsedRealtime() - start}ms")
            assertTrue(duration > 0L)
        }
    }
}
