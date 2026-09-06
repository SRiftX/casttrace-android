package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DouyinMetadataTest {
    @Test fun refreshUpdatesFallbackNameAndRoomTitle() = runBlocking {
        val id = InstrumentationRegistry.getArguments().getString("douyinRoomId")?.toLongOrNull()
        assumeTrue("Requires configured Douyin room", id != null)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as StreamCapApplication
        app.container.repository.refresh(id!!)
        val room = requireNotNull(app.container.repository.room(id))
        assertFalse(room.anchorName.startsWith("抖音主播 "))
        assertFalse(room.roomTitle.isNullOrBlank())
        android.util.Log.i("DouyinMetadataTest", "PASS refreshed anchor and title")
        Unit
    }
}
