package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.data.database.LiveRoomEntity
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@dagger.hilt.android.testing.HiltAndroidTest
class ManualPauseSwitchTest : HiltGraphTest() {
    @Test fun manualPauseDisablesOnlyItsRoomAutomaticRecording(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext).testGraph
        val dao = container.database.liveRoomDao()
        val future = System.currentTimeMillis() + 86_400_000L
        val first = dao.insert(LiveRoomEntity(
            originalUrl = "https://qa.invalid/pause-switch-one",
            normalizedUrl = "https://qa.invalid/pause-switch-one",
            platform = "qaPause", anchorName = "QA pause one", roomTitle = null,
            scheduledMonitoringEnabled = true, autoRecord = true, oneTimeStartAt = future,
        ))
        val second = dao.insert(LiveRoomEntity(
            originalUrl = "https://qa.invalid/pause-switch-two",
            normalizedUrl = "https://qa.invalid/pause-switch-two",
            platform = "qaPause", anchorName = "QA pause two", roomTitle = null,
            scheduledMonitoringEnabled = true, autoRecord = true, oneTimeStartAt = future,
        ))
        try {
            context.startService(RecordingService.pauseIntent(context, first))
            withTimeout(10_000) {
                while (container.repository.room(first)?.taskStatus != TaskStatus.PAUSED) delay(50)
            }
            val paused = container.repository.room(first)!!
            val untouched = container.repository.room(second)!!
            assertFalse(paused.scheduledMonitoringEnabled)
            assertFalse(paused.autoRecord)
            assertTrue(untouched.scheduledMonitoringEnabled)
            assertTrue(untouched.autoRecord)
            assertEquals(TaskStatus.PAUSED, paused.taskStatus)
        } finally {
            container.repository.deleteRoom(first)
            container.repository.deleteRoom(second)
        }
    }
}
