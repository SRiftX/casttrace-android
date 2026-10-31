package com.streamcap.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real stream timing run. Pass roomId through instrumentation arguments. */
@dagger.hilt.android.testing.HiltAndroidTest
class LiveSaveTimingTest : HiltGraphTest() {
    @Test fun timePauseOfExistingLiveRoom(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext).testGraph.repository
        val roomId = InstrumentationRegistry.getArguments().getString("roomId")?.toLongOrNull()
        assumeTrue("Specify a roomId on an idle QA device", roomId != null && repository.activeSessions().isEmpty())
        assumeTrue("Room must exist", repository.room(roomId!!) != null)
        RecordingService.start(context, roomId)
        try {
            withTimeout(60_000) {
                while (repository.room(roomId)?.taskStatus != TaskStatus.RECORDING) delay(200)
            }
            delay(20_000)
            val start = android.os.SystemClock.elapsedRealtime()
            RecordingService.pauseIntent(context, roomId).also(context::startService)
            withTimeout(180_000) {
                while (repository.room(roomId)?.taskStatus != TaskStatus.PAUSED) delay(200)
            }
            android.util.Log.i("StreamCapSave", "visible pause room=$roomId elapsed=${android.os.SystemClock.elapsedRealtime() - start}ms")
            assertEquals(TaskStatus.PAUSED, repository.room(roomId)?.taskStatus)
        } finally {
            if (repository.room(roomId)?.taskStatus in setOf(TaskStatus.RECORDING, TaskStatus.RECONNECTING, TaskStatus.STARTING)) {
                context.startService(RecordingService.pauseIntent(context, roomId))
            }
        }
    }
}
