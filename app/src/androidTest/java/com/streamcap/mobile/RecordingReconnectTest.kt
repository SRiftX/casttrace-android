package com.streamcap.mobile

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

@dagger.hilt.android.testing.HiltAndroidTest
class RecordingReconnectTest : HiltGraphTest() {
    @Test fun pauseWhileWaitingPreventsAutomaticRestart() = runBlocking {
        val roomId = InstrumentationRegistry.getArguments().getString("reconnectRoomId")?.toLongOrNull()
        assumeTrue("Requires a configured room with unavailable streams", roomId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext).testGraph.repository
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2000)
        withContext(Dispatchers.Main) { RecordingService.start(context, roomId!!) }
        try {
            withTimeout(180000) {
                repository.observeRoom(roomId!!).first { it?.taskStatus == TaskStatus.RECONNECTING }
            }
            withContext(Dispatchers.Main) {
                context.startService(RecordingService.pauseIntent(context, roomId!!))
            }
            withTimeout(10000) {
                repository.observeRoom(roomId!!).first { it?.taskStatus == TaskStatus.PAUSED }
            }
            // Cross the maximum retry delay and at least two monitor polls.
            repeat(35) {
                delay(1000)
                assertEquals(TaskStatus.PAUSED, repository.room(roomId!!)?.taskStatus)
            }
        } finally {
            withContext(Dispatchers.Main) {
                context.startService(RecordingService.pauseIntent(context, roomId!!))
            }
        }
    }
}
