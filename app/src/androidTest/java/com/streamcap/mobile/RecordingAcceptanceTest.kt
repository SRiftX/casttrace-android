package com.streamcap.mobile

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcap.mobile.domain.model.RoomRecordingOptions
import com.streamcap.mobile.domain.model.TaskStatus
import com.streamcap.mobile.service.RecordingService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in network/device acceptance: pass recordingRoomId or recordingRoomUrl. */
@dagger.hilt.android.testing.HiltAndroidTest
class RecordingAcceptanceTest : HiltGraphTest() {
    @Test fun recordTenMinutesAndPublish() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val requestedRoomId = args.getString("recordingRoomId")?.toLongOrNull()
        val roomUrl = args.getString("recordingRoomUrl")
        assumeTrue("Requires explicit live room ID or URL", requestedRoomId != null || !roomUrl.isNullOrBlank())
        val requestedSeconds = args.getString("recordingSeconds")
            ?.toLongOrNull()?.coerceIn(600L, 3600L) ?: 600L
        val outputFormat = args.getString("recordingOutputFormat")?.uppercase() ?: "FLV"
        require(outputFormat in setOf("FLV", "MP4", "TS")) { "不支持的测试输出格式: $outputFormat" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext
        val repository = app.testGraph.repository
        val roomId = requestedRoomId ?: repository.rooms.first().firstOrNull { it.normalizedUrl == roomUrl }?.id
            ?: repository.addRoom(requireNotNull(roomUrl), options = RoomRecordingOptions(outputFormat = outputFormat, segmentEnabled = false))
        val room = requireNotNull(repository.room(roomId))
        val sources = repository.streams(roomId)
        assertTrue("${room.platform} 当前没有可录制线路", sources.isNotEmpty())
        android.util.Log.i("RecordingAcceptance", "SOURCE platform=${room.platform} room=$roomId types=${sources.map { it.type }.distinct()}")
        val baseline = repository.recordings.first().maxOfOrNull { it.id } ?: 0
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        delay(2000)
        withContext(Dispatchers.Main) { RecordingService.start(context, roomId) }
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
            var lastProgressAt = start
            var lastBytes = 0L
            while (SystemClock.elapsedRealtime() - start < (requestedSeconds + 20L) * 1000L) {
                delay(10000)
                val session = repository.session(sessionId)!!
                if (session.totalBytes > lastBytes) {
                    lastBytes = session.totalBytes
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                android.util.Log.i("RecordingAcceptance", "session=$sessionId elapsed=${(SystemClock.elapsedRealtime()-start)/1000} bytes=${session.totalBytes} mediaSeconds=${session.durationSeconds} status=${session.status}")
                assertTrue("Recording ended early: ${session.errorMessage}", session.status in setOf(TaskStatus.RECORDING, TaskStatus.RECONNECTING))
                assertTrue("No media progress for 90 seconds", SystemClock.elapsedRealtime() - lastProgressAt < 90000)
            }
        } finally {
            withContext(Dispatchers.Main) { context.startService(RecordingService.pauseIntent(context, roomId)) }
        }
        val completed = withTimeout(120000) {
            repository.observeRecording(sessionId).first { it?.status in setOf(TaskStatus.PAUSED, TaskStatus.COMPLETED, TaskStatus.FAILED) }!!
        }
        assertEquals(completed.errorMessage, TaskStatus.PAUSED, completed.status)
        assertTrue("Actual published media did not meet requested duration", completed.durationSeconds >= requestedSeconds)
        assertTrue(completed.outputUris.isNotEmpty())
        completed.outputUris.forEach { uri ->
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { assertTrue(it.read() >= 0) }
        }
        completed.danmakuPath?.let { path ->
            assertTrue("弹幕资产缺失", File(path).isFile)
            android.util.Log.i("RecordingAcceptance", "DANMAKU platform=${room.platform} session=$sessionId bytes=${File(path).length()} count=${completed.danmakuEventCount} dropped=${completed.danmakuDroppedCount}")
        }
        android.util.Log.i("RecordingAcceptance", "PASS platform=${room.platform} session=$sessionId duration=${completed.durationSeconds} bytes=${completed.totalBytes} uris=${completed.outputUris}")
        Unit
    }
}
