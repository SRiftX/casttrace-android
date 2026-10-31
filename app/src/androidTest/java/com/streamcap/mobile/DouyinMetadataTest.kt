package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@dagger.hilt.android.testing.HiltAndroidTest
class DouyinMetadataTest : HiltGraphTest() {
    @Test fun refreshUpdatesFallbackNameAndRoomTitle() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("douyinRoomId")?.toLongOrNull()
        assumeTrue("Requires configured Douyin room", id != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        app.testGraph.repository.refresh(id!!)
        val room = requireNotNull(app.testGraph.repository.room(id))
        assertFalse(room.anchorName.startsWith("抖音主播 "))
        assertFalse(room.roomTitle.isNullOrBlank())
        android.util.Log.i("DouyinMetadataTest", "PASS refreshed anchor and title")
        Unit
    }
}
