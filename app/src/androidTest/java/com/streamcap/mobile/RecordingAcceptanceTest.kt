package com.streamcap.mobile

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.app.StreamCapApplication
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network/device acceptance: pass recordingRoomId to record a configured live room. */
class RecordingAcceptanceTest {
    @Test fun recordTenMinutesAndPublish() = runBlocking {
        val roomId = InstrumentationRegistry.getArguments().getString("recordingRoomId")?.toLongOrNull()
        assumeTrue("Requires explicit live room ID", roomId != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as StreamCapApplication
        val repository = app.container.repository
        val baseline = repository.recordings.first().maxOfOrNull { it.id } ?: 0
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2000)
        withContext(Dispatchers.Main) { RecordingService.start(context, roomId!!) }
        var sessionId = 0L
        try {
            withTimeout(120000) {
                while (sessionId == 0L) {
                    sessionId = repository.recordings.first().firstOrNull {
                        it.roomId == roomId && it.id > baseline && it.totalBytes > 0
                    }?.id ?: 0
                    delay(1000)
                }
            }
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < 620000) {
                delay(10000)
                val session = repository.session(sessionId)!!
                android.util.Log.i("RecordingAcceptance", "session=$sessionId elapsed=${(SystemClock.elapsedRealtime()-start)/1000} bytes=${session.totalBytes} mediaSeconds=${session.durationSeconds} status=${session.status}")
                assertTrue("Recording ended early: ${session.errorMessage}", session.status == TaskStatus.RECORDING)
            }
        } finally {
            withContext(Dispatchers.Main) { context.startService(RecordingService.pauseIntent(context, roomId!!)) }
        }
        val completed = withTimeout(120000) {
            repository.observeRecording(sessionId).first { it?.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED) }!!
        }
        assertEquals(completed.errorMessage, TaskStatus.PAUSED, completed.status)
        assertTrue("Actual published media must exceed ten minutes", completed.durationSeconds >= 600)
        assertTrue(completed.outputUris.isNotEmpty())
        android.util.Log.i("RecordingAcceptance", "PASS session=$sessionId duration=${completed.durationSeconds} uris=${completed.outputUris}")
        Unit
    }
}
